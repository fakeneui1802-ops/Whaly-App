# Plugin regression fixtures

These files are manual tests for the plugin manager and recovery flow. They are not loaded by the app or executed by Gradle.

| Fixture | Expected result |
| --- | --- |
| `01_healthy_plugin.whalyplugin` | Install, render the UI, exercise callbacks and settings, then unload cleanly. |
| `02_install_failure.whalyplugin` | Installation fails and the manager shows an error without retaining an active plugin. |
| `03_render_failure.whalyplugin` | A render error is contained and reported by the plugin UI. |
| `04_fatal_crash_safe_mode.whalyplugin` | Deliberately terminate the process, then verify startup recovery and safe mode. Use a test installation. |
| `05_dex_interface.whalyplugin` | Load the bundled DEX interface and display its return value. Source is under `dex-source`. |

Keep generated DEX build output under `dex-build`, which is ignored by Git. When testing a release APK, also check icon lookup and the DEX bridge after R8 shrinking.
