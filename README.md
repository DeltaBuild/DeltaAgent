# DeltaAgent

Javaagent unique pour serveur MCPC+ 1.6.4 : Java 17, monde de Y -64 à Y 319 et identifiants larges de NGClient, sans toucher au jar du serveur ni aux mods.

DeltaAgent remplace [mcpc-java17](https://github.com/Loyto-SKHR/mcpc-java17) et [DeltaHeight](https://github.com/DeltaBuild/DeltaHeight). Il avance par étapes ; chaque étape garde le serveur jouable.

## État : étape 4

- **java17** : reprise de mcpc-java17 2.1.0 à l'identique (version de Java simulée, modules du JDK ouverts, `sun.reflect`, `Launch`, ASM 9.7).
- **hauteur** : reprise de DeltaHeight 1.0.0 (24 sections, protocole NGClient), avec en plus le patch AntiXray, le superflat réglable par monde et la lecture des maps de NGHeight.
- **IDs** : registres de blocs et d'objets qui grandissent jusqu'à 2^24 IDs comme dans NGClient, objets au-delà de 31 999 sauvegardés et transmis au format NGClient (`idExt`), chunks qui stockent des IDs de bloc sur 24 bits (`Add2`, `BlocksB3`) des métadonnées sur 16 bits (`DataAdd`, `Data2`) et des biomes sur 16 bits (`Biomes16`), envoyés aux joueurs dans les blocs de données de NGClient.

## Prérequis

- MCPC+ 1.6.4 build 164-251 (Forge 9.11.1.965). La classe `Chunk` est compilée pour ce build, les autres builds de MCPC+ et Cauldron ne sont pas pris en charge.
- Java 17 ([Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17)).
- Le client NGClient pour les joueurs. Un client 1.6.4 classique ne sait pas lire les chunks en 24 sections et se fait déconnecter.

## Installation

1. Faire une sauvegarde de la map avant le premier lancement.
2. Copier `DeltaAgent.jar` dans le dossier du serveur.
3. Mettre `-javaagent:DeltaAgent.jar` dans la commande de lancement, avant `-jar` (ou avant la classe principale), et retirer les anciens agents : `mcpc-java17-agent.jar`, `ngheight-agent.jar`, `DeltaHeight.jar`. DeltaAgent refuse de démarrer s'ils sont encore là.

   ```
   java -javaagent:DeltaAgent.jar -Xmx8G -jar mcpc.jar nogui
   ```

Les options `-XX:+UseConcMarkSweepGC` et `-XX:CMS...` n'existent plus en Java 17 et empêchent le démarrage : il faut les retirer.

Au démarrage, la console doit afficher :

```
[DeltaAgent/core] DeltaAgent 0.5.0-rc.1 on Java 17.0.x (modules: java17, height, ids).
[DeltaAgent/java17] Java 17.0.x detected, reporting 1.7.0_80 to bypass the MCPC+ version check.
[DeltaAgent/height] World extended to Y -64..319 (24 sections), NGClient protocol (NGNEID marker).
[DeltaAgent/ids] Block and item registries start at 131072 IDs and grow up to 16777216. ...
[DeltaAgent/core] Game transformer installed after cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer (47 patched classes).
...
[DeltaAgent/height] Replaced net.minecraft.world.chunk.Chunk.
[DeltaAgent/ids] Replaced net.minecraft.world.chunk.storage.ExtendedBlockStorage.
```

`max-build-height=256` dans `server.properties` passe à 320.

### Venir de NGHeight ou de DeltaHeight

Les maps se chargent sans conversion : même format, et les chunks marqués `NGHeight` sont reconnus comme ceux de DeltaHeight. Un fichier `ngheight-flat.properties` déjà présent dans une map est relu tel quel (voir Superflat).

Retour en arrière : NGHeight et DeltaHeight relisent les maps sauvegardées par DeltaAgent, sauf les objets au-delà de 31 999 et les blocs au-delà de 4095 (voir Identifiants).

### Venir de mcpc-java17 ou d'un DeltaAgent plus ancien

Les maps des étapes 1 et 2 se chargent sans conversion. `-Dmcpcj17.idCapacity` n'est plus lu : les registres grandissent seuls. Les objets de 32 768 à 65 535 que ces versions sauvegardaient en `short` (relus en négatif, donc perdus) sont relus avec le bon ID si l'objet existe. Les mods qui s'arrêtaient à 65 535 (NGCore) enregistrent maintenant aussi leurs blocs au-delà.

## Hauteur du monde

### Format de sauvegarde

Toujours du format Anvil, avec le même principe que la 1.18 :

- le tag `Y` des sections est signé et va de -4 à 19 ;
- la heightmap contient des Y absolus (-64 = colonne vide) ;
- un tag `DeltaHeight` marque les chunks enregistrés au format étendu.

Les maps 1.6.4 existantes se chargent sans conversion, rien ne change entre Y 0 et Y 255. Une map qui a des blocs sous Y 0 ou au-dessus de Y 255 ne doit jamais tourner sans le module de hauteur : le serveur n'arrive pas à lire ces chunks et les régénère.

Pour revenir à un serveur sans hauteur étendue : tant qu'aucun bloc n'a été posé hors de 0..255, la map reste lisible. Après, il faut remettre la sauvegarde.

### Protocole réseau

Ce qui change par rapport à la 1.6.4 vanilla (compatible NGClient) :

- marqueur `Packet250` sur le canal `NGNEID`, envoyé juste après `Packet1Login` ;
- Packet51 / Packet56 : masques de sections en `int` (24 bits) au lieu de `short` ;
- Packet14 / 15 / 53 : Y en `short` signé au lieu d'un octet ;
- Packet52 : envoyé seulement si tous les Y sont entre 0 et 255, sinon remplacé par des Packet53 ;
- Packet61 (sons et particules) : pas envoyé si Y est hors de 0..255.

### Fonctionnement

Le module se place juste après la déobfuscation de FML, les coremods passent donc par-dessus normalement. Les principales modifications :

- `Chunk` est remplacée (24 sections, heightmap, lumière et entités de -64 à 319) ;
- `AnvilChunkLoader` lit et écrit les 24 sections ;
- `World` et quelques autres classes passent leurs limites de 0..255 à -64..319, collisions comprises ;
- les paquets réseau listés plus haut ;
- l'API Bukkit (`CraftWorld`, `CraftChunk`, `CraftBlock`, `ChunkSnapshot`) fonctionne sur -64..319 ;
- l'anti-xray de Spigot traite les 24 sections, avec le bon Y.

Les plugins WorldEdit 5.6 (et AsyncWorldEdit) et VoxelSniper 5.168 sont aussi patchés au chargement pour accepter les Y négatifs. Avec une autre version, si le code attendu n'est pas trouvé, la classe est laissée telle quelle et la console le signale.

### Superflat

Dans les mondes superflat, les nouveaux chunks reçoivent des couches sous Y 0. Elles se règlent dans le fichier `deltaagent-flat.properties` du dossier de chaque monde, créé avec les valeurs par défaut au premier chunk généré :

```
enabled=true
bottom=7:0
fill=1:0
replaceBedrockAt0=true
```

- `bottom` : bloc à Y -64 (bedrock par défaut) ;
- `fill` : bloc de Y -63 à -1 (pierre par défaut) ;
- `replaceBedrockAt0` : remplace la bedrock vanilla de Y 0 par `fill` ;
- `enabled=false` : garde le superflat vanilla.

Les blocs s'écrivent `id:meta`, les IDs de mods sont acceptés. Les couches du preset ne bougent pas (par défaut 2 de terre et l'herbe à Y 3), et les chunks déjà générés ne sont pas modifiés. Si le monde n'a pas de `deltaagent-flat.properties` mais un `ngheight-flat.properties`, c'est celui-là qui est lu.

### legacyView

Avec la hauteur étendue, le tableau de sections d'un chunk (`getBlockStorageArray()`) a 24 cases et la section Y 0 est à l'index 4. Un mod fait pour la 1.6.4 classique qui lit ce tableau avec `sections[y >> 4]` tape donc 64 blocs trop bas, voire en dehors du tableau.

Les classes passées dans l'option `deltaagent.height.legacyView` reçoivent à la place une vue de 16 sections (Y 0 à 255) avec l'ancien index. Elles ne voient pas ce qui est hors de 0..255 mais marchent comme avant.

C'est déjà fait pour les générateurs superflat et Bukkit, `ChunkHelper` (NGUpgrades) et `MovementUtility` (Calclavia). L'option sert seulement si un autre mod pose problème.

### Limites connues

- Seul le `WorldProvider` de base est modifié, une dimension de mod qui redéfinit `getHeight()` garde sa hauteur.
- WorldEdit `//regen` ne régénère pas la partie sous Y 0.
- Packet17 (lit) et Packet61 (effets) ne sont pas envoyés hors de 0..255, le client lit Y sur un octet.

## Identifiants

### Registres

Les tableaux de blocs et d'objets démarrent à 131 072 cases, comme dans NGClient. Quand un mod crée un bloc ou un objet qui ne rentre pas, ils doublent jusqu'à le contenir, au maximum 16 777 216 (2^24, IDs 0 à 16 777 215). Tous les tableaux indexés par ID grandissent ensemble : ceux de `Block`, `Item`, les statistiques, les matériaux Bukkit, les marqueurs de `Configuration` de Forge et les blocs que les endermen peuvent porter. Un ID hors de 0..16 777 215 donne une erreur claire.

Un agrandissement est signalé dans la console : un mod qui a gardé une copie des anciens tableaux ne verrait pas les nouveaux IDs. Le message donne la taille à mettre dans `-Ddeltaagent.ids.capacity` pour partir directement à la bonne taille.

### Blocs au-delà de 4095 et métadonnées au-delà de 15

Ils se posent, se sauvegardent et s'envoient aux joueurs comme les autres : IDs jusqu'à 16 777 215, métadonnées jusqu'à 65 535.

**Sauvegarde.** Chaque section garde ses IDs sur quatre plans et ses métadonnées sur trois :

| Tag | Contenu | Taille |
| --- | --- | --- |
| `Blocks` | ID, bits 0 à 7 | 4096 octets |
| `Add` | ID, bits 8 à 11 | 2048 octets |
| `Add2` | ID, bits 12 à 15 | 2048 octets |
| `BlocksB3` | ID, bits 16 à 23 | 4096 octets |
| `Data` | méta, bits 0 à 3 | 2048 octets |
| `DataAdd` | méta, bits 4 à 7 | 2048 octets |
| `Data2` | méta, bits 8 à 15 | 4096 octets |

`Add2`, `BlocksB3`, `Data2` sont les noms de NGClient ; les sources de NationsGlory ne nomment pas le plan des bits 4 à 7, `DataAdd` est le choix de DeltaAgent. Les plans au-delà du vanilla ne sont écrits que s'ils contiennent autre chose que des zéros : une section sans bloc au-delà de 4095 ni méta au-delà de 15 est sauvegardée comme en vanilla. Les sections au format palette des serveurs NationsGlory (`Palette`, `PaletteData`, `PaletteBits`) sont lues, puis réécrites avec `Add2` et `BlocksB3` à la sauvegarde suivante. Un bloc dont l'ID n'est plus enregistré (mod retiré) devient de l'air au chargement, comme MCPC+ le fait déjà.

Dès qu'une section avec ces plans est lue ou sauvegardée, le `level.dat` reçoit `NGBlockIdFormat` : 1 pour `Add2`, 2 pour une palette lue, 3 pour `BlocksB3`, 4 pour les métadonnées sur 16 bits. Une map marquée ne démarre plus avec `-Ddeltaagent.ids=false` (voir Sécurités) : un serveur sans ces plans lirait d'autres blocs à la place, ou de l'air, et le garderait à la sauvegarde suivante.

**Réseau.** Seul Packet56 (envoi de chunks) peut porter les plans hauts. Après les données vanilla de chaque chunk, il contient les cinq blocs que NGClient attend, dans cet ordre, chacun avec son en-tête (code, version 1, masque de sections sur 24 bits) :

| Bloc | Contenu | Par section |
| --- | --- | --- |
| `NGWL` | eau dans les blocs (vide) | 512 octets |
| `NGID` | `Add2` | 2048 octets |
| `NGMD` | métadonnées bits 4 à 7 (2048 octets) puis 8 à 15 (4096) | 6144 octets |
| `NGBI` | biomes bits 8 à 15, si le chunk a `Biomes16` (voir Biomes) | 256 octets par chunk |
| `NGB3` | `BlocksB3` | 4096 octets |

Vides, ces blocs ajoutent 42 octets par chunk. Les autres paquets de blocs sont remplacés juste avant l'envoi quand ils ne peuvent pas porter le bloc :

- Packet53 (un bloc) porte un ID sur 16 bits et une méta sur 8 : au-delà, le chunk entier part en Packet56 ;
- Packet52 (plusieurs blocs) porte un ID sur 12 bits et une méta sur 4 : au-delà, il devient un Packet53 par bloc, ou le chunk part en Packet56 si un bloc dépasse aussi Packet53 ;
- Packet51 (sections modifiées) : NGClient efface `Add2` et `BlocksB3` des sections qu'il reçoit et garde ses anciens plans de méta, donc des sections qui ont l'un de ces plans partent en Packet56.

Pendant une même mise à jour de chunk, un joueur reçoit le chunk entier au plus une fois. Packet56 recrée le chunk côté client : les tile entities du chunk sont renvoyées juste après, et les entités qui s'y trouvent réapparaissent pour ce joueur (Packet29 puis nouvel envoi).

**Le reste du serveur.** `ChunkSnapshot` et `getTypeId()` de Bukkit donnent l'ID complet. Une méta hors de 0..65 535 donne une erreur qui indique la position, comme dans NGClient : le vanilla gardait les 4 bits du bas sans rien dire. L'anti-xray de Spigot (coupé par défaut dans MCPC+) ne cache pas un bloc au-delà de 255 dont l'octet bas ressemble à un minerai. Sur les cartes (objet map), un bloc au-delà de 4095 prend la couleur de son matériau.

**Limites.**

- NGClient refuse un chunk de plus de 393 216 octets une fois décompressé. Avec la hauteur étendue, un chunk peut dépasser quand une vingtaine de ses sections ont `BlocksB3` (21 au plus si toutes ont aussi `Add` et `Add2`, 15 si elles ont en plus des métas au-delà de 15) : la pose qui ferait dépasser est refusée, et la console le signale. Un chunk déjà trop gros (posé par un autre serveur, directement dans la section, ou sans le module de hauteur avec tous les plans dans ses 16 sections) est signalé à l'envoi.
- Bukkit passe la méta en `byte` (`Block.getData()`, `setData()`) et `ChunkSnapshot.getBlockData()` la lit sur 4 bits : au-delà, il faut passer par le code du serveur.
- Particules de casse (Packet61), blocs qui tombent (Packet23) et événements de bloc (Packet54, coffres, pistons) gardent les limites vanilla : NGClient les lit sur 12 ou 16 bits.

### Biomes au-delà de 255

La liste des biomes (`BiomeGenBase.biomeList`) et celle de `BiomeDictionary` passent de 256 à 65 536 cases, la taille fixe de NGClient : un mod peut enregistrer un biome jusqu'à 65 535.

Chaque chunk garde l'octet bas de ses biomes dans `Biomes`, comme en vanilla, et l'octet haut dans un tableau `Biomes16` de 256 octets (nom de NGClient). Ce tableau est créé dès qu'un biome dépasse 255 et n'est sauvegardé que s'il sert ; le `level.dat` reçoit alors `NGBlockIdFormat=5`. Une colonne encore à calculer vaut 255 sans `Biomes16` et 65 535 avec, comme dans NGClient. Un biome dont l'ID n'est plus enregistré se lit comme des plaines, comme en vanilla.

- Génération : les générateurs (vanilla, mods, Bukkit) n'écrivent que l'octet bas. Juste après la génération d'un chunk, DeltaAgent complète `Biomes16` à partir de la source de biomes du monde, partout où son biome a le même octet bas. Rien ne change tant qu'aucun biome au-delà de 255 n'est enregistré.
- Bukkit : `World.setBiome()` écrit l'ID complet.
- Réseau : le bloc `NGBI` de Packet56 porte `Biomes16`, et un Packet51 de chunk entier part en Packet56 quand le chunk en a.

### Objets au-delà de 31 999

Ils sont écrits comme dans NGClient :

- sauvegarde : `id` vaut 0 (en `short`) et l'ID réel est dans un `int` `idExt` ;
- réseau : le `short` vaut 0 et `idExt` est ajouté à une copie du tag de l'objet, puis retiré à la lecture ;
- Packet20 (joueur qui apparaît) : l'objet en main est envoyé à 0, le Packet5 qui suit envoie le vrai.

Dès qu'un tel objet est sauvegardé, le `level.dat` des mondes reçoit `NGItemIdFormat=1`, comme sur les serveurs NationsGlory, et la marque reste ensuite. Un serveur qui ne connaît pas `idExt` relirait ces objets avec l'ID 0 et les supprimerait à la sauvegarde suivante.

### Attribution des IDs par Forge

Quand la valeur par défaut d'un mod est déjà prise, Forge cherche un ID libre. Pour les blocs, DeltaAgent le cherche dans la même fenêtre que NGClient, de 19475 à 16337 en descendant, puis sous 4096 comme avant ; les IDs entre 4096 et la fenêtre ne sont jamais choisis. La fenêtre se règle avec `-Ddeltaagent.ids.blockWindow=min-max`. Pour les objets, rien ne change (sous 32 000). Les IDs déjà écrits dans les fichiers de configuration ne bougent pas.

### Serveur NationsGlory

Si le serveur a sa propre prise en charge des grands IDs (classe `NGCapaciteIds`), le module ne s'active pas.

## Sécurités

DeltaAgent arrête le serveur (code de sortie 42) plutôt que de tourner à moitié et d'abîmer la map :

- au démarrage, si `mcpc-java17-agent.jar`, `ngheight-agent.jar` ou `DeltaHeight.jar` est aussi chargé ;
- au démarrage, si le module de hauteur est désactivé (`-Ddeltaagent.height=false`) alors que la map a été sauvegardée avec la hauteur étendue. La vérification lit les fichiers `region` de la map (`level-name` de `server.properties`), de ses dimensions et des autres dossiers du serveur qui contiennent un `level.dat` ; elle s'arrête au premier chunk marqué ;
- au chargement d'un monde dont le `level.dat` déclare un format d'IDs que le serveur ne sait pas lire : `NGBlockIdFormat` supérieur à 5, `NGItemIdFormat` supérieur à 1, ou l'un des deux avec `-Ddeltaagent.ids=false` ;
- si une modification importante échoue (stockage, sauvegarde, protocole, registres) ou si `Chunk` n'a pas été remplacée.

## Options

À ajouter avant `-jar`, sous la forme `-Dnom=valeur`. Les anciens noms restent acceptés, avec un message dans la console.

| Option | Défaut | Effet |
| --- | --- | --- |
| `deltaagent.height` | `true` | `false` désactive le module de hauteur (monde de 0 à 255) |
| `deltaagent.height.legacyView` | vide | Classes qui reçoivent la vue 16 sections, séparées par des virgules (voir legacyView). Anciens noms : `deltaheight.legacyView`, `ngheight.legacyView` |
| `deltaagent.height.guard` | `true` | `false` désactive la vérification de hauteur de la map quand le module est désactivé |
| `deltaagent.ids` | `true` | `false` garde les limites vanilla (blocs 0-4095, objets 0-31999) ; les mondes marqués `NGItemIdFormat` ou `NGBlockIdFormat` sont alors refusés. `-Ddeltaagent.ids.capacity=off` marche encore |
| `deltaagent.ids.capacity` | `131072` | Taille de départ des registres de blocs et d'objets, de 4096 à 16777216, arrondie à la puissance de 2 supérieure. Ils grandissent ensuite seuls si besoin. `mcpcj17.idCapacity` n'est plus lu |
| `deltaagent.ids.blockWindow` | `16337-19475` | Fenêtre où Forge choisit les IDs de bloc libres (voir Identifiants). Ancien nom : `ng.blocs.fenetre` (celui de NGClient) |
| `deltaagent.ids.forgetWorldFormat` | `false` | `true` pour un seul démarrage : le `NGItemIdFormat` et le `NGBlockIdFormat` lus dans les `level.dat` ne sont pas gardés, ils ne sont réécrits que si un objet au-delà de 31 999 ou une section au-delà de 4095 est sauvegardé. Sert à retirer la marque d'une map qui n'en a plus. Ancien nom : `ngid.formatmonde.oublier` (celui de NationsGlory) |
| `deltaagent.debug` | `false` | Affiche la liste des transformers et plus de détails au démarrage. Ancien nom : `deltaheight.debug` |

## Compiler

Il faut un JDK 17. Le build ne télécharge rien d'autre que Gradle et ses dépendances, et n'a pas besoin du jar du serveur :

```
./gradlew build
```

Le jar sort dans `build/libs/DeltaAgent.jar`, après les tests. La version (0.5.0-rc.1 par défaut) se change dans `build.gradle` ; sur un tag `vX.Y.Z`, GitHub Actions construit le jar avec la version du tag et le publie dans une release, et sur un tag `vX.Y.Z-rc.N` dans une pre-release.

### Classes de remplacement et classes runtime

`Chunk` et `ExtendedBlockStorage` (dans `src/replace/java`) sont compilées contre `libs/mcpc-stubs.jar`, qui contient seulement les signatures des classes MCPC+ utilisées (pas de code), puis ramenées en bytecode Java 6.

Les classes de `src/runtime/java` (package `fr.deltabuild.deltaagent.runtime`) sont appelées par les classes du jeu patchées et manipulent des types du jeu (NBT, `ItemStack`, sections, paquets) : elles sont aussi compilées contre les stubs et les classes de remplacement, et c'est le LaunchClassLoader qui les charge, comme une classe de mod. Au démarrage, elles sont copiées dans un dossier temporaire ajouté à son classpath.

Si l'une de ces classes utilise de nouvelles classes ou méthodes de MCPC+, il faut régénérer les stubs :

1. copier le jar MCPC+ du serveur dans `libs/minecraft_server.jar` ;
2. générer `libs/mcpc-srg.jar` (commandes en haut de `tools/Deobf.java`) ;
3. lancer `./gradlew mcpcStubs`.

### Bancs de comparaison

Deux bancs vérifient que DeltaAgent modifie les classes comme les agents qu'il remplace. Ils ont besoin de `libs/mcpc-srg.jar` (étape 2 ci-dessus) :

- `./gradlew compareMcpcJava17`, avec `libs/mcpc-java17-agent.jar` : les patchs Java 17 doivent être identiques (les IDs factices de mcpc-java17 ne sont plus comparés, le module d'IDs les a remplacés). Résultat attendu : `different: 0` et `Bundled ASM and Launch: identical`.
- `./gradlew compareDeltaHeight`, avec `libs/DeltaHeight.jar` (1.0.0) : les patchs de hauteur doivent être identiques, sauf les changements voulus listés par le banc (`Chunk` et `AntiXray`). Résultat attendu : `unexpected differences: 0`.

Quand `libs/mcpc-srg.jar` est présent, `./gradlew test` applique aussi les patchs d'IDs aux vraies classes de MCPC+, vérifie que la section remplacée garde tous les membres de celle de MCPC+, et teste les classes runtime avec les vraies classes NBT et `NibbleArray` (sauvegarde des plans, palettes, Packet56 relu comme NGClient le lit) ; sans lui (GitHub Actions), ces tests sont sautés.

D'autres jars peuvent être ajoutés avec `-PbenchJars=chemin/a.jar,chemin/b.jar` (par exemple `WorldEdit.jar` et `VoxelSniper.jar` pour le banc de hauteur). Ces jars contiennent du code Minecraft ou d'autres projets : ils sont dans le `.gitignore` et ne doivent pas être publiés.

### Arborescence

```
src/main/java/fr/deltabuild/deltaagent/
  DeltaAgent.java    point d'entrée (premain)
  core/              options, journaux, sécurités, transformer de jeu
  java17/            module Java 17 (ex mcpc-java17)
  height/            module de hauteur (ex DeltaHeight), patchs des plugins
  ids/               module d'IDs : registres, patchs (IdPatches, StoragePatches, BiomePatches), fenêtre Forge
src/main/java/org/objectweb/asm/LegacyAsmApi.java   appelé par l'ASM patché servi au serveur
src/replace/java/    classes remplacées en entier (Chunk, ExtendedBlockStorage)
src/runtime/java/    classes chargées par le LaunchClassLoader :
                       ItemIds (objets), WorldFormats (level.dat), SectionIds (plans des sections),
                       ChunkBiomes (Biomes16), ChunkPackets (paquets de blocs), Snapshots, XrayIds,
                       MapHistogram
src/bench/java/      bancs de comparaison avec mcpc-java17 et DeltaHeight
tools/               Deobf et Stubs
libs/                launchwrapper 1.8 et stubs MCPC+ (compilation seulement)
```

L'agent utilise sa propre copie d'ASM, relocalisée sous `fr.deltabuild.deltaagent.shadow.asm` : il ne dépend jamais de l'ASM chargé par le serveur. L'ASM 9.7 servi au serveur à la place de l'ASM 4 de MCPC+ reste sous `org.objectweb.asm`.

## Licence

DeltaAgent est sous licence MIT (voir `LICENSE`). Le code repris de mcpc-java17 reste sous sa licence MIT d'origine ; le jar embarque ASM (BSD 3-Clause, voir `licenses/ASM-LICENSE.txt`) et une classe `Launch` dérivée de launchwrapper (MIT). `src/replace/java/net/minecraft/world/chunk/Chunk.java` et `src/replace/java/net/minecraft/world/chunk/storage/ExtendedBlockStorage.java` sont dérivés du code du serveur Minecraft / MCPC+ et `libs/mcpc-stubs.jar` contient des signatures de classes MCPC+ : ces parties ne sont pas couvertes par la licence MIT.
