LIVE MAPS

A high-performance Spigot/Paper plugin that enables dynamic, real-time player tracking on in-game Minecraft maps displayed within Item Frames. Perfect for creating large, functional map walls and observation decks.

FEATURES

Real-Time Tracking: Player positions, direction, and colors are updated live on the map.
High Performance: Optimized rendering logic to minimize server load.
Customizable Cursors: Players can select their preferred map cursor color via command.
GUI Map Generation: Use the interactive /livemap grid command to easily create and center large 3x3 or 5x5 map walls.
Persistent Maps: Renderers are tied to the map IDs, ensuring they persist across server restarts.
Configurable Toggles: Toggle player name tags and Y-level tracking via in-game commands.

INSTALLATION & BUILDING
This project uses Maven for dependency management and building.

Requirements

Java Development Kit (JDK) 17 or newer
Maven

Building the Plugin

Clone the Repository:
'git clone https://github.com/LoafersMC/LiveMaps'
'cd LiveMaps'

Build with Maven:
'mvn clean package'
The final plugin file, LiveMaps.jar, will be found in the target/ directory.

Deploying to Your Server

Stop your Minecraft server.
Place LiveMaps.jar into your server's plugins/ folder.
Start your Minecraft server.

USAGE AND COMMANDS

All primary configuration and creation commands start with /livemap (or the alias /lm).

COMMAND: /livemap use
DESCRIPTION: Applies the LiveMap renderer to the map you are currently holding.
PERMISSION: livemap.use

COMMAND: /livemap create
DESCRIPTION: Generates a new live map centered at your current location and gives it to you.
PERMISSION: livemap.create

COMMAND: /livemap grid
DESCRIPTION: Opens a 5x5 GUI for easily selecting and creating a tiled map wall centered on your location.
PERMISSION: livemap.create

COMMAND: /livemap togglenames
DESCRIPTION: Toggles the visibility of player name tags above their cursor.
PERMISSION: livemap.config

COMMAND: /livemap toggley
DESCRIPTION: Toggles the display of player Y-Level (altitude) on the cursor.
PERMISSION: livemap.config

COMMAND: /livemap setupdaterate <ticks>
DESCRIPTION: Sets how frequently the map canvas is refreshed (20 ticks = 1 second).
PERMISSION: livemap.config

COMMAND: /livemap refresh
DESCRIPTION: Forces a refresh of all currently rendered maps.
PERMISSION: livemap.config

COMMAND: /livemap reload
DESCRIPTION: Reloads the plugin configuration files.
PERMISSION: livemap.config

COMMAND: /livemap togglenameplate
DESCRIPTION: Toggles the visibility of the Item Frame's nameplate (if named).
PERMISSION: livemap.config

LICENSE

This project is licensed under the MIT License. See the LICENSE file for details.
