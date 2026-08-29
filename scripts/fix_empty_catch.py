#!/usr/bin/env python3
"""Fix empty catch blocks by adding Log.d statements."""
import os, re

root = r"C:\GitHub\Lxchat\app\src\main\java"

# Files with empty catch blocks to fix
FIXES = [
    # (file, old_pattern, new_pattern)
    (r"com\lxseek\chat\tool\ScreenRecordToolProvider.kt",
     r"try { c.stop() } catch (_: Exception) {}",
     r"try { c.stop() } catch (e: Exception) { Log.d(\"ScreenRecord\", \"stop failed\", e) }"),
    (r"com\lxseek\chat\tool\ScreenRecordToolProvider.kt",
     r"try { c.release() } catch (_: Exception) {}",
     r"try { c.release() } catch (e: Exception) { Log.d(\"ScreenRecord\", \"release failed\", e) }"),
    (r"com\lxseek\chat\tool\ScreenRecordToolProvider.kt",
     r"try { v.release() } catch (_: Exception) {}",
     r"try { v.release() } catch (e: Exception) { Log.d(\"ScreenRecord\", \"release failed\", e) }"),
    (r"com\lxseek\chat\tool\ScreenRecordToolProvider.kt",
     r"try { m.release() } catch (_: Exception) {}",
     r"try { m.release() } catch (e: Exception) { Log.d(\"ScreenRecord\", \"release failed\", e) }"),
    (r"com\lxseek\chat\tool\ScreenRecordToolProvider.kt",
     r"try { mediaProjection.stop() } catch (_: Exception) {}",
     r"try { mediaProjection.stop() } catch (e: Exception) { Log.d(\"ScreenRecord\", \"stop failed\", e) }"),
    (r"com\lxseek\chat\tool\DeviceToolProvider.kt",
     r"} catch (_: Exception) { }",
     r"} catch (e: Exception) { Log.d(\"DeviceTool\", \"operation failed\", e) }"),
    (r"com\lxseek\chat\ui\settings\SettingsAppearancePage.kt",
     r"} catch (_: Exception) {}",
     r"} catch (e: Exception) { Log.d(\"SettingsAppearance\", \"parse failed\", e) }"),
    (r"com\lxseek\chat\ui\settings\SettingsGenerationPage.kt",
     r"} catch (_: Exception) {}",
     r"} catch (e: Exception) { Log.d(\"SettingsGeneration\", \"parse failed\", e) }"),
    (r"com\lxseek\chat\ui\settings\SettingsSandboxPage.kt",
     r"} catch (_: Exception) {}",
     r"} catch (e: Exception) { Log.d(\"SettingsSandbox\", \"operation failed\", e) }"),
    (r"com\lxseek\chat\util\NetworkMonitor.kt",
     r"} catch (_: Exception) {}",
     r"} catch (e: Exception) { Log.d(\"NetworkMonitor\", \"callback failed\", e) }"),
    (r"com\lxseek\chat\util\TtsManager.kt",
     r"} catch (_: Exception) {}",
     r"} catch (e: Exception) { Log.d(\"TtsManager\", \"operation failed\", e) }"),
    (r"com\lxseek\chat\viewmodel\VoiceConversationController.kt",
     r"} catch (_: Exception) {}",
     r"} catch (e: Exception) { Log.d(\"VoiceConv\", \"operation failed\", e) }"),
]

fixed_count = 0
for rel_path, old, new in FIXES:
    full_path = os.path.join(root, rel_path)
    if not os.path.exists(full_path):
        print(f"SKIP (not found): {rel_path}")
        continue
    content = open(full_path, 'r', encoding='utf-8').read()
    if old in content:
        content = content.replace(old, new, 1)  # Replace first occurrence only
        open(full_path, 'w', encoding='utf-8').write(content)
        fixed_count += 1
        print(f"FIXED: {rel_path}")
    else:
        print(f"SKIP (pattern not found): {rel_path}")

print(f"\nTotal fixed: {fixed_count}")