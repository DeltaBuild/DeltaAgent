# DeltaAgent

Javaagent unique pour serveur MCPC+ 1.6.4 : Java 17, monde de Y -64 à Y 319 et identifiants larges de NGClient, sans toucher au jar du serveur ni aux mods.

DeltaAgent remplace [mcpc-java17](https://github.com/Loyto-SKHR/mcpc-java17) et [DeltaHeight](https://github.com/DeltaBuild/DeltaHeight). Il avance par étapes ; chaque étape garde le serveur jouable.

## État : étape 0

Cette version ne change rien au jeu par rapport à mcpc-java17 :

- **java17** : reprise de mcpc-java17 2.1.0 à l'identique (version de Java simulée, modules du JDK ouverts, `sun.reflect`, `Launch`, ASM 9.7) ;
- **IDs factices** : comme mcpc-java17, les blocs au-delà de 4095 sont enregistrés mais ne peuvent pas être posés. C'est provisoire, la vraie prise en charge arrive aux étapes 2 et 3 ;
- **transformer de jeu** : installé juste après la déobfuscation de FML, il est encore vide et recevra les modules suivants.

La hauteur étendue reste gérée par NGHeight ou DeltaHeight jusqu'à l'étape 1.

## Prérequis

- MCPC+ 1.6.4 build 164-251 (Forge 9.11.1.965).
- Java 17 ([Eclipse Temurin 17](https://adoptium.net/temurin/releases/?version=17)).

## Installation

1. Faire une sauvegarde de la map.
2. Copier `DeltaAgent.jar` dans le dossier du serveur.
3. Dans la commande de lancement, remplacer `-javaagent:mcpc-java17-agent.jar` par `-javaagent:DeltaAgent.jar`, toujours avant `-jar` (ou avant la classe principale). Si la map utilise la hauteur étendue, garder l'agent de hauteur juste après :

   ```
   java -javaagent:DeltaAgent.jar -javaagent:ngheight-agent.jar -Xmx8G -jar mcpc.jar nogui
   ```

Les options `-XX:+UseConcMarkSweepGC` et `-XX:CMS...` n'existent plus en Java 17 et empêchent le démarrage : il faut les retirer.

Au démarrage, la console doit afficher :

```
[DeltaAgent/core] DeltaAgent 0.1.0 on Java 17.0.x (modules: java17, ids placeholders).
[DeltaAgent/java17] Java 17.0.x detected, reporting 1.7.0_80 to bypass the MCPC+ version check.
[DeltaAgent/height] NGHeight handles the world height for now.
...
[DeltaAgent/ids] Raising the ID limits to 65536 (blocks above 4095 are placeholders).
[DeltaAgent/core] Game transformer installed after cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer (0 patched classes).
```

## Sécurités

DeltaAgent arrête le serveur avant le chargement du monde (code de sortie 42) dans ces cas :

- `mcpc-java17-agent.jar` est aussi chargé : DeltaAgent le remplace, il faut retirer son `-javaagent`.
- La map a été sauvegardée avec la hauteur étendue (chunks marqués par NGHeight ou DeltaHeight) et aucun agent de hauteur n'est chargé. Sans lui, MCPC+ ne sait pas lire ces chunks et les régénère. La vérification lit les fichiers `region` de la map (`level-name` de `server.properties`), de ses dimensions et des autres dossiers du serveur qui contiennent un `level.dat` ; elle s'arrête au premier chunk marqué.

## Options

À ajouter avant `-jar`, sous la forme `-Dnom=valeur`.

| Option | Défaut | Effet |
| --- | --- | --- |
| `deltaagent.ids.capacity` | `65536` | Taille des tableaux de blocs et d'objets. `off` désactive les IDs factices. Ancien nom accepté : `mcpcj17.idCapacity` |
| `deltaagent.height.guard` | `true` | `false` désactive la vérification de hauteur de la map |
| `deltaagent.debug` | `false` | Affiche la liste des transformers au démarrage |

## Compiler

Il faut un JDK 17 :

```
./gradlew build
```

Le jar sort dans `build/libs/DeltaAgent.jar`, après les tests. La version (0.1.0 par défaut) se change dans `build.gradle` ; sur un tag `vX.Y.Z`, GitHub Actions construit le jar avec la version du tag et le publie dans une release.

### Banc de comparaison avec mcpc-java17

L'étape 0 doit modifier les classes exactement comme mcpc-java17. Le banc applique les patchs des deux agents aux mêmes classes et compare le résultat :

1. copier `libs/mcpc-srg.jar` depuis DeltaHeight (jar MCPC+ en noms SRG, voir son README) ;
2. copier `mcpc-java17-agent.jar` dans `libs/` ;
3. lancer :

   ```
   ./gradlew compareMcpcJava17
   ```

   D'autres jars peuvent être ajoutés avec `-PbenchJars=chemin/a.jar,chemin/b.jar` (par exemple `ngheight-agent.jar`, dont la classe `Chunk` est patchée elle aussi).

Résultat attendu : `different: 0` et `Bundled ASM and Launch: identical`. Ces jars contiennent du code Minecraft ou d'autres projets : ils sont dans le `.gitignore` et ne doivent pas être publiés.

### Arborescence

```
src/main/java/fr/deltabuild/deltaagent/
  DeltaAgent.java    point d'entrée (premain)
  core/              options, journaux, sécurités, transformer de jeu
  java17/            module Java 17 (ex mcpc-java17)
  ids/legacy/        IDs factices de mcpc-java17, provisoires
  height/            vérification de hauteur de la map
src/main/java/org/objectweb/asm/LegacyAsmApi.java   appelé par l'ASM patché servi au serveur
src/bench/java/      banc de comparaison avec mcpc-java17
libs/                launchwrapper 1.8 (compilation seulement)
```

L'agent utilise sa propre copie d'ASM, relocalisée sous `fr.deltabuild.deltaagent.shadow.asm` : il ne dépend jamais de l'ASM chargé par le serveur. L'ASM 9.7 servi au serveur à la place de l'ASM 4 de MCPC+ reste sous `org.objectweb.asm`.

## Licence

DeltaAgent est sous licence MIT (voir `LICENSE`). Le code repris de mcpc-java17 reste sous sa licence MIT d'origine ; le jar embarque ASM (BSD 3-Clause, voir `licenses/ASM-LICENSE.txt`) et une classe `Launch` dérivée de launchwrapper (MIT).
