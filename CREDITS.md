# Credits

Ogi's code is our own, but many of its ideas come from other robots. Their authors open-sourced them or wrote them up
on the [RoboWiki](https://robowiki.net/), and this file says which ideas they are. It lists what we took from a
specific robot or author. Techniques that the RoboWiki describes as common knowledge are not listed, for example
GuessFactor and KNN targeting, wave surfing, bullet shadows, minimum risk movement, melee targeting of every enemy at
once and team communication. No other robot's code is in Ogi, and its numbers are tuned by our own measurements.

| Idea | From | Where in Ogi |
|---|---|---|
| Bullet powers just past a rounding boundary: surfers that match a hit to its wave by the bullet speed rounded to tenths fail to learn our hits | [DrussGT](https://robowiki.net/wiki/DrussGT) (Skilgannon), also used by [BeepBoop](https://robowiki.net/wiki/BeepBoop) (Kev) | `gun.BulletPower` |
| Bullet shielding: standing still and stepping aside by a tiny wiggle only when needed; a family of heading predictors, the offset chosen by exact matches with the enemy's bullets; plans of wiggle and power scored by the shadow | BeepBoop (Kev); the enemy-gun identification also from DrussGT (Skilgannon) | `shield.ShieldPlanner`, `shield.ShieldAim` |
| The shield's bullet power capped by the energy race | BeepBoop (Kev), DrussGT (Skilgannon) | `shield.Shield` |
| Shield every enemy first, then surf for the rest of the battle once the shield stops paying | DrussGT (Skilgannon) | `shield.ShieldTrial` |
| An ensemble danger model: simulated simple guns, a learning model and a flattener, each weighted by how well it explained the enemy's bullets | BeepBoop (Kev), "crowd surfing" | `move.DangerModel`, `move.SimpleAims` |
| Pattern matching on our lateral velocity as one of the danger estimators | BeepBoop (Kev) | `move.SimpleAims`, `move.LateralHistory` |
| Path surfing: paths that change direction once, and the previous plan kept as a candidate | BeepBoop (Kev) | `move.WaveSurfer` |
| Stopping at the least dangerous point of a wave instead of at the end of a way | [ScalarR](https://robowiki.net/wiki/ScalarR) (Xor), BeepBoop (Kev) | `move.WaveSurfer` |
| Shadow shots: among good aims, fire the one whose bullet shadows protect us most | BeepBoop (Kev), active bullet shadowing | `move.WaveSurfer` |
| An anti-surfer gun: nearest neighbours over the waves of our real bullets, in views of the latest bullets and of all of them | [Diamond](https://robowiki.net/wiki/Diamond) (Voidious) | `gun.GunModel` |
| The anti-surfer gun used while a confidence bound of the main gun's hit rate stays low | BeepBoop (Kev) | `gun.GunModel` |
| Danger that grows when a way closes in on the shooter | Diamond (Voidious) | `move.WaveSurfer` |
| Against rammers: the rammer simulated along each way, collisions included, with its next shot | BeepBoop (Kev) | `move.WaveSurfer`, `move.RamDetector` |
| Bullet power in the energy race: energy taken from the enemy per turn against energy spent, the hit rate scaled by the escape angle | BeepBoop (Kev) | `gun.RacePower` |
| The melee risk form: the enemy's threat over the distance squared and over the robots closer to it, more risk along the line to an enemy, the threat capped | Diamond (Voidious), [HawkOnFire](https://robowiki.net/wiki/HawkOnFire) (rozu) | `move.MeleeMovement` |
| Melee movement that adds the danger of enemy waves to the position's risk, each over its median | [Neuromancer](https://robowiki.net/wiki/Neuromancer) (Skilgannon) | `move.MeleeMovement`, `move.EnemyWaves` |

The engine model in `cz.libors.ogi.physics` repeats the arithmetic of [Robocode](https://github.com/robo-code/robocode)'s
`RobotPeer` and `BulletPeer` (Eclipse Public License 1.0) so that it matches the engine bit for bit.
`docs/engine-model.md` names the source of each engine fact.

Thanks to all of them, and to everyone who keeps the RoboWiki and the RoboRumble alive.
