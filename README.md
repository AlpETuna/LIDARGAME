# LIDAR Cave Explorer

A first-person exploration game where you navigate procedurally generated caves in complete darkness using only a LIDAR scanner to reveal your surroundings.

![LIDAR Example](LIDAR%20example.png)

## Features

- **LIDAR Scanning**: Hold left-click to spray thermal LIDAR dots that reveal the environment
- **Thermal Gradient**: Dots change color based on distance (red = close, yellow = medium, blue = far)
- **Procedural Caves**: 3D Perlin noise generates organic cave structures
- **Physics**: Full gravity and jumping mechanics
- **Testing Mode**: Press T to toggle visibility and see the cave generation

## Controls

- **WASD**: Move around
- **Mouse**: Look around
- **Space**: Jump
- **Left Click**: Spray LIDAR dots
- **T**: Toggle testing mode (shows/hides geometry)
- **ESC**: Free/capture mouse cursor

## Technical Details

- Built with libGDX
- Multi-octave Perlin noise for terrain generation
- Real-time raycasting for LIDAR simulation
- 3D collision detection with AABB
- Thermal color gradient based on distance

## Building & Running

```bash
gradle desktop:run
```

## Requirements

- Java 17+
- Gradle 9.2+
