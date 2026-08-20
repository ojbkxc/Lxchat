package buildlogic

import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applies [RemoveFirstLastFix] to every Android variant, rewriting the Android 15
 * `removeFirst()`/`removeLast()` calls in app + dependency classes at build time.
 *
 * Applied via `plugins { id("buildlogic.removefirstlast-fix") }` in a module that also applies AGP.
 */
class RemoveFirstLastFixPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val androidComponents =
            project.extensions.getByType(AndroidComponentsExtension::class.java)

        androidComponents.onVariants { variant ->
            variant.instrumentation.transformClassesWith(
                RemoveFirstLastFix::class.java,
                InstrumentationScope.ALL,
            ) {}
            variant.instrumentation.setAsmFramesComputationMode(
                FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
            )
        }
    }
}
