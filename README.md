# Vamp Survivor (Android)

A lightweight Android game prototype inspired by **Vampire Survivors**. The project showcases:

- Virtual joystick movement rendered on a `SurfaceView`
- Automatic projectile attacks against endlessly spawning enemies
- Wave-based enemy difficulty scaling, including boss encounters
- Player progression with experience, level-ups, and random upgrade choices
- Pause/resume, autosave/load, vibration feedback, and basic audio effects
- Simple menus for starting, resuming, or loading a saved run plus character selection

## Getting started

1. Open the project in **Android Studio Hedgehog (or newer)**.
2. Let Gradle sync and download dependencies.
3. Connect an Android device (API 26+) or start an emulator.
4. Run the **app** configuration.

### Controls

- Use the virtual joystick in the bottom-left corner to move.
- Attacks trigger automatically at the nearest enemy.
- Tap **Pause** to open the pause overlay; resume or quit from there.
- When you level up, pick one of three random upgrades to enhance your build.

### Saving & Loading

The game autosaves every few seconds and when the app is backgrounded. The main menu lets you continue the last run or load saved progress even after a relaunch.

## Project structure

```
app/
├── src/main/java/com/example/vampsurvivor/
│   ├── GameActivity.kt, GameView.kt – core loop and rendering
│   ├── entities/ – player, enemy, projectile, and item models
│   └── systems/ – audio, persistence, loop controller, joystick helpers
├── src/main/res/ – layouts, theming, and placeholder audio assets
└── build.gradle.kts – Android module configuration
```

Feel free to extend the prototype with new weapons, enemy types, or visual/audio polish!
