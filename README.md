Presence is a time and chunk based claiming plugin.
The longer a chunk is populated by a player, the higher his score rises, if the player has the highest amount within that chunk, it will automatically gain access to the chunk.



## Important to note quirks:

   - Due to how the plugins works, right now everyone is able to claim every chunk (neutral claims will be added soon)
   - The plugin does not protected against explosions (this will be changed soon) and lava/water flows
   - The plugin does not care about the world (yeah, defo need to fix that)


## Features:

   - Placeholder API support
   - Fly within claims via /claimfly (can be turned off)
   - Claim passing notifications
   - Map of claims via /claims map
   - Player trust system
   - Integrated scoreboard (visible via /claims togglesb)


## Placeholder documentation:

no required placeholder API exapnsion.
All placeholders require the player scope to be known

  - **%presence_claimowner%** <br>
   Obtains the name of the owner of the chunk the player is currently standing on, returns "none" if not applicable

  - **%presence_ownerpresence%** <br>
   Obtains the presence of the owner of the chunk the player is currently standing on, returns "0" if not applicable

  - **%presence_claimsuccessor%** <br>
   Obtains the name of the follow-up leader of the chunk the player is currently standing on, returns "none" if not applicable

  - **%presence_successorpresence%** <br>
   Obtains the presence of the follow-up leader of the chunk the player is currently standing on, returns "0" if not applicable

  - **%presence_playerpresence%** <br>
   Obtains the presence the player has on the chunk it is standing on


## A note on performance

Due to the chunk-based approach of the plugin, it will be relatively optimised. I try to optimise other aspects of the plugin, however from time to time this simply cannot be done logically so I would gladly accept any results from profilers (again: Timings will usually not be too great of a helper there)

---

Please note that this plugin is very early in it's public development phase, a few bugs may exist and I strongly recommend you to report these
