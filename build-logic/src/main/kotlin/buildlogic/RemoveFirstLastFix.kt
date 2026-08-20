package buildlogic

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Build-time bytecode fix for the Android 15 `removeFirst()` / `removeLast()` incompatibility.
 *
 * Java 21 added `removeFirst()` / `removeLast()` instance methods to `java.util.List`
 * (via `SequencedCollection`). Code compiled against the Android 15 (API 35) SDK resolves
 * `list.removeFirst()` / `list.removeLast()` to those new platform members, which do **not**
 * exist on Android 14 and earlier -> `NoSuchMethodError` at runtime.
 *
 * Some precompiled dependencies (e.g. `org.jetbrains:markdown`, whose `GeneratedLexerKt.pop`
 * was compiled this way) ship the offending `invokevirtual` directly in their bytecode, and the
 * upstream has no fixed release. Core library desugaring does **not** backport these methods,
 * so the only robust fix is to rewrite the call sites at build time.
 *
 * This [AsmClassVisitorFactory] runs over **all** classes (app + dependencies) during dexing and
 * rewrites every no-arg `removeFirst()`/`removeLast()` on `List`-typed receivers into the
 * always-available `remove(int)` equivalent:
 *   - `list.removeFirst()` -> `list.remove(0)`
 *   - `list.removeLast()`  -> `list.remove(list.size() - 1)`
 *
 * Receivers that natively have these methods (e.g. `Deque` / `LinkedList` / `ArrayDeque`) are left
 * untouched, so no semantics change.
 */
abstract class RemoveFirstLastFix : AsmClassVisitorFactory<InstrumentationParameters.None> {

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = RemoveFirstLastClassVisitor(nextClassVisitor)

    // Visit everything; the rewrite only fires on exact matching call sites, which is cheap.
    override fun isInstrumentable(classData: ClassData): Boolean = true
}

private class RemoveFirstLastClassVisitor(
    next: ClassVisitor,
) : ClassVisitor(Opcodes.ASM9, next) {

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        return RemoveFirstLastMethodVisitor(mv)
    }
}

private class RemoveFirstLastMethodVisitor(
    next: MethodVisitor,
) : MethodVisitor(Opcodes.ASM9, next) {

    override fun visitMethodInsn(
        opcode: Int,
        owner: String,
        name: String,
        descriptor: String,
        isInterface: Boolean,
    ) {
        val isTargetReceiver = owner in LIST_OWNERS
        val isTargetCall = descriptor == "()Ljava/lang/Object;" &&
            (name == "removeFirst" || name == "removeLast")

        if (!isTargetReceiver || !isTargetCall) {
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
            return
        }

        // Receiver `list` is already on the operand stack.
        if (name == "removeFirst") {
            // -> remove(0)
            super.visitInsn(Opcodes.ICONST_0)
        } else {
            // -> remove(size() - 1)
            super.visitInsn(Opcodes.DUP)                                   // [list, list]
            super.visitMethodInsn(opcode, owner, "size", "()I", isInterface) // [list, size]
            super.visitInsn(Opcodes.ICONST_1)                             // [list, size, 1]
            super.visitInsn(Opcodes.ISUB)                                 // [list, size - 1]
        }
        super.visitMethodInsn(opcode, owner, "remove", "(I)Ljava/lang/Object;", isInterface)
    }

    private companion object {
        // `List` subtypes that gained removeFirst/removeLast only in Java 21 and all expose
        // remove(int)/size(). Deque/LinkedList/ArrayDeque are intentionally excluded.
        val LIST_OWNERS = setOf(
            "java/util/List",
            "java/util/ArrayList",
            "java/util/AbstractList",
        )
    }
}
