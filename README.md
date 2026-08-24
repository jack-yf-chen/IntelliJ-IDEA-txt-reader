# Novel Reader

Novel Reader is a lightweight IntelliJ Platform plugin for reading local TXT novels inside IntelliJ IDEA.

Target IDE for the first version:

- IntelliJ IDEA 2026.1.3
- IntelliJ Platform build branch 261

Main features:

- `Tools -> Novel Reader` action.
- `Novel Reader` tool window.
- TXT loading with UTF-8, GB18030, and GBK fallback.
- Basic chapter detection.
- Previous and next chapter navigation.
- Font size adjustment.
- Reading state persistence.

Build:

```powershell
gradle buildPlugin
```

The generated plugin ZIP will be under `build/distributions/`.

If `gradle` is not available on `PATH`, install Gradle or open this directory as a Gradle project in IntelliJ IDEA and run the `buildPlugin` task from the Gradle tool window.
