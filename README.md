# DeltaAgent

Javaagent unique pour serveur MCPC+ 1.6.4 : Java 17, monde de Y -64 à Y 319 et identifiants larges de NGClient, sans toucher au jar du serveur ni aux mods.

DeltaAgent remplace [mcpc-java17](https://github.com/Loyto-SKHR/mcpc-java17) et [DeltaHeight](https://github.com/DeltaBuild/DeltaHeight). Il avance par étapes ; chaque étape garde le serveur jouable.

## État : étape 1

- **java17** : reprise de mcpc-java17 2.1.0 à l'identique (version de Java simulée, modules du JDK ouverts, `sun.reflect`, `Launch`, ASM 9.7).
- **hauteur** : reprise de DeltaHeight 1.0.0 (24 sections, protocole NGClient), avec en plus le patch AntiXray, le superflat réglable par monde et la lecture des maps de NGHeight.
- **IDs factices** : comme mcpc-java17, les blocs au-delà de 4095 sont enregistrés mais ne peuvent pas être posés. C'est provisoire, la vraie prise en charge arrive aux étapes 2 et 3.

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
[DeltaAgent/core] DeltaAgent 0.2.0 on Java 17.0.x (modules: java17, height, ids placeholders).
[DeltaAgent/java17] Java 17.0.x detected, reporting 1.7.0_80 to bypass the MCPC+ version check.
[DeltaAgent/height] World extended to Y -64..319 (24 sections), NGClient protocol (NGNEID marker).
...
[DeltaAgent/ids] Raising the ID limits to 65536 (blocks above 4095 are placeholders).
[DeltaAgent/core] Game transformer installed after cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer (33 patched classes).
...
[DeltaAgent/height] Replaced net.minecraft.world.chunk.Chunk.
```

`max-build-height=256` dans `server.properties` passe à 320.

### Venir de NGHeight ou de DeltaHeight

Les maps se chargent sans conversion : même format, et les chunks marqués `NGHeight` sont reconnus comme ceux de DeltaHeight. Un fichier `ngheight-flat.properties` déjà présent dans une map est relu tel quel (voir Superflat).

Retour en arrière : NGHeight et DeltaHeight relisent les maps sauvegardées par DeltaAgent.

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

## Sécurités

DeltaAgent arrête le serveur (code de sortie 42) plutôt que de tourner à moitié et d'abîmer la map :

- au démarrage, si `mcpc-java17-agent.jar`, `ngheight-agent.jar` ou `DeltaHeight.jar` est aussi chargé ;
- au démarrage, si le module de hauteur est désactivé (`-Ddeltaagent.height=false`) alors que la map a été sauvegardée avec la hauteur étendue. La vérification lit les fichiers `region` de la map (`level-name` de `server.properties`), de ses dimensions et des autres dossiers du serveur qui contiennent un `level.dat` ; elle s'arrête au premier chunk marqué ;
- si une modification importante échoue (stockage, sauvegarde, protocole) ou si `Chunk` n'a pas été remplacée.

## Options

À ajouter avant `-jar`, sous la forme `-Dnom=valeur`. Les anciens noms restent acceptés, avec un message dans la console.

| Option | Défaut | Effet |
| --- | --- | --- |
| `deltaagent.height` | `true` | `false` désactive le module de hauteur (monde de 0 à 255) |
| `deltaagent.height.legacyView` | vide | Classes qui reçoivent la vue 16 sections, séparées par des virgules (voir legacyView). Anciens noms : `deltaheight.legacyView`, `ngheight.legacyView` |
| `deltaagent.height.guard` | `true` | `false` désactive la vérification de hauteur de la map quand le module est désactivé |
| `deltaagent.ids.capacity` | `65536` | Taille des tableaux de blocs et d'objets. `off` désactive les IDs factices. Ancien nom : `mcpcj17.idCapacity` |
| `deltaagent.debug` | `false` | Affiche la liste des transformers au démarrage. Ancien nom : `deltaheight.debug` |

## Compiler

Il faut un JDK 17. Le build ne télécharge rien d'autre que Gradle et ses dépendances, et n'a pas besoin du jar du serveur :

```
./gradlew build
```

Le jar sort dans `build/libs/DeltaAgent.jar`, après les tests. La version (0.2.0 par défaut) se change dans `build.gradle` ; sur un tag `vX.Y.Z`, GitHub Actions construit le jar avec la version du tag et le publie dans une release.

### Classes de remplacement

`Chunk` (dans `src/replace/java`) est compilée contre `libs/mcpc-stubs.jar`, qui contient seulement les signatures des classes MCPC+ utilisées (pas de code), puis ramenée en bytecode Java 6. Si une classe de remplacement utilise de nouvelles classes ou méthodes de MCPC+, il faut régénérer les stubs :

1. copier le jar MCPC+ du serveur dans `libs/minecraft_server.jar` ;
2. générer `libs/mcpc-srg.jar` (commandes en haut de `tools/Deobf.java`) ;
3. lancer `./gradlew mcpcStubs`.

### Bancs de comparaison

Deux bancs vérifient que DeltaAgent modifie les classes comme les agents qu'il remplace. Ils ont besoin de `libs/mcpc-srg.jar` (étape 2 ci-dessus) :

- `./gradlew compareMcpcJava17`, avec `libs/mcpc-java17-agent.jar` : les patchs Java 17 et IDs factices doivent être identiques. Résultat attendu : `different: 0` et `Bundled ASM and Launch: identical`.
- `./gradlew compareDeltaHeight`, avec `libs/DeltaHeight.jar` (1.0.0) : les patchs de hauteur doivent être identiques, sauf les changements voulus listés par le banc (`Chunk` et `AntiXray`). Résultat attendu : `unexpected differences: 0`.

D'autres jars peuvent être ajoutés avec `-PbenchJars=chemin/a.jar,chemin/b.jar` (par exemple `WorldEdit.jar` et `VoxelSniper.jar` pour le banc de hauteur). Ces jars contiennent du code Minecraft ou d'autres projets : ils sont dans le `.gitignore` et ne doivent pas être publiés.

### Arborescence

```
src/main/java/fr/deltabuild/deltaagent/
  DeltaAgent.java    point d'entrée (premain)
  core/              options, journaux, sécurités, transformer de jeu
  java17/            module Java 17 (ex mcpc-java17)
  height/            module de hauteur (ex DeltaHeight), patchs des plugins
  ids/legacy/        IDs factices de mcpc-java17, provisoires
src/main/java/org/objectweb/asm/LegacyAsmApi.java   appelé par l'ASM patché servi au serveur
src/replace/java/    classes remplacées en entier (Chunk)
src/bench/java/      bancs de comparaison avec mcpc-java17 et DeltaHeight
tools/               Deobf et Stubs
libs/                launchwrapper 1.8 et stubs MCPC+ (compilation seulement)
```

L'agent utilise sa propre copie d'ASM, relocalisée sous `fr.deltabuild.deltaagent.shadow.asm` : il ne dépend jamais de l'ASM chargé par le serveur. L'ASM 9.7 servi au serveur à la place de l'ASM 4 de MCPC+ reste sous `org.objectweb.asm`.

## Licence

DeltaAgent est sous licence MIT (voir `LICENSE`). Le code repris de mcpc-java17 reste sous sa licence MIT d'origine ; le jar embarque ASM (BSD 3-Clause, voir `licenses/ASM-LICENSE.txt`) et une classe `Launch` dérivée de launchwrapper (MIT). `src/replace/java/net/minecraft/world/chunk/Chunk.java` est dérivé du code du serveur Minecraft / MCPC+ et `libs/mcpc-stubs.jar` contient des signatures de classes MCPC+ : ces parties ne sont pas couvertes par la licence MIT.
