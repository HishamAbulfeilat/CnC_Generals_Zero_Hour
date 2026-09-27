/*
**	Command & Conquer Generals Zero Hour(tm)
**	Copyright 2025 Electronic Arts Inc.
**
**	This program is free software: you can redistribute it and/or modify
**	it under the terms of the GNU General Public License as published by
**	the Free Software Foundation, either version 3 of the License, or
**	(at your option) any later version.
**
**	This program is distributed in the hope that it will be useful,
**	but WITHOUT ANY WARRANTY; without even the implied warranty of
**	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
**	GNU General Public License for more details.
**
**	You should have received a copy of the GNU General Public License
**	along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

// GeneralsX @bugfix HishamAbulfeilat 27/09/2026 Online service hostnames.
//
// cmake/gamespy.cmake points the GameSpy SDK at a replacement service
// (GAMESPY_SERVER_NAME, e.g. server.cnc-online.net) and passes the same name to game
// code as RTS_GAMESPY_SERVER_NAME. The SDK's GSI_DOMAIN_NAME only rewrites hostnames
// the SDK builds itself; the game also hardcodes EA/GameSpy hosts, and some SDK
// hostnames ("ccgenzh.ms6.<domain>", "ccgenzh.master.<domain>") do not exist on
// C&C Online. On PC the C&C Online launcher fixes both by redirecting DNS lookups;
// without it (Android, or any build run directly) "Online" fails at the servserv
// lookup. The mapping below mirrors the launcher's (xan105/CnC-Online, src/online.h).

#pragma once

#ifdef RTS_GAMESPY_SERVER_NAME
	// servserv (patch / map-pack / MOTD / config files)
	#define ONLINE_SERVSERV_HOST   "http." RTS_GAMESPY_SERVER_NAME
	// server browser, QR2 heartbeats and availability check
	#define ONLINE_MASTER_HOST     "master." RTS_GAMESPY_SERVER_NAME
	#define ONLINE_GAMESTATS_HOST  "gamestats." RTS_GAMESPY_SERVER_NAME
	#define ONLINE_PEERCHAT_HOST   "peerchat." RTS_GAMESPY_SERVER_NAME
	// players-online counter (launch.gamespyarcade.com on retail)
	#define ONLINE_ARCADE_HOST     RTS_GAMESPY_SERVER_NAME
#else
	#define ONLINE_SERVSERV_HOST   "servserv.generals.ea.com"
	#define ONLINE_GAMESTATS_HOST  "gamestats.gamespy.com"
	#define ONLINE_PEERCHAT_HOST   "peerchat.gamespy.com"
	#define ONLINE_ARCADE_HOST     "launch.gamespyarcade.com"
#endif
