Presence is a time and chunk based claiming plugin.

The score rises the longer a player is within a chunk. The player with the highest amount within a chunk will automatically gain access to the chunk,
while others will be denied entry.

## Important to note quirks:

   - Due to how the plugins works, right now everyone is able to claim every chunk (neutral claims might be added one day)
   - The plugin does not protect against lava/water flows


## Features:

   - Placeholder API support
   - Fly within claims via /claimfly (can be turned off)
   - Claim passing notifications
   - Map of claims via /claims map
   - Player trust system
   - Integrated scoreboard (visible via /claims togglesb)
   - Abillity for users to create chunk groups, which have their own permissions set up (usefull for public farms)
   - Abillity for players to allow/disallow certain actions for different people (also usefull for public farms)
   - Mostly atomic backend (i. e. other plugins can access as much data as they want on another thread without there being a performance penalty)


## Placeholder documentation:

No Placeholder API exapnsion is required for placeholders and PAPI needn't be installed in order for this plugin to work,
though using it might be benificial. All placeholders require the player scope to be known, so they may not
always be usable.

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
