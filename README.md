# MCP JDWP Inspector

Serveur MCP (Model Context Protocol) pour inspecter et **contrôler** des applications Java en temps réel via JDWP en utilisant JDI (Java Debug Interface).

Permet à Claude Code d'inspecter l'état d'une JVM pendant l'exécution ET de contrôler l'exécution (resume, step over, step into, step out).

## Architecture

```
Claude Code
    ↓ (MCP Protocol via STDIO)
Spring Boot MCP Server (21 tools)
    ↓ (JDI - Java Debug Interface)
JDWP Protocol
    ↓
debuggerX Proxy (port DEBUGGERX_PROXY_PORT=55005)
    ↓
Tomcat/Application Java (port JVM_JDWP_PORT=61959)
```

**Ports configurables:**
- `JVM_JDWP_PORT` (défaut: 61959) - Port JDWP de la JVM
- `DEBUGGERX_PROXY_PORT` (défaut: 55005) - Port du proxy (IntelliJ + MCP Inspector)

**Note:** debuggerX est un proxy qui permet à plusieurs debuggers de se connecter simultanément. Voir [lib/debuggerX-README.md](lib/debuggerX-README.md) pour plus de détails.

## Fonctionnalités

✅ **Inspection complète**
- Liste des threads avec statut
- Stack traces complètes
- Variables locales à chaque frame
- Champs d'objets avec navigation récursive

✅ **Collections intelligentes**
- Vues optimisées pour ArrayList, LinkedHashMap, HashSet
- Affichage du contenu (éléments, entrées key=value)
- Navigation dans les tableaux

✅ **Contrôle d'exécution**
- Resume/Suspend de threads
- Step Over, Step Into, Step Out
- Gestion des breakpoints (set/clear/list)
- Contrôle total du debugger via IA

✅ **Surveillance d'événements**
- Capture tous les événements JDWP en temps réel
- Détection des breakpoints (même ceux posés par IntelliJ)
- Monitoring des steps, exceptions, modifications de threads
- Historique des 100 derniers événements

✅ **Évaluation d'expressions (Watchers)**
- Évaluation d'expressions Java arbitraires au breakpoint
- Compilation dynamique avec classpath complet (571 entrées)
- Support des strings, primitives, objets et méthodes
- Cache de compilation pour performance
- Gestion automatique des proxies (Guice, CGLIB)


## Prérequis

- **JDK 17+** (avec `tools.jar` pour JDI)
- **Gradle 8.11+** (inclus via wrapper)
- **Application Java en mode debug JDWP**

## Installation

### 1. Build du projet

```bash
cd mcp-jdwp-java
./gradlew.bat build
```

Cela crée : `build/libs/mcp-jdwp-java-1.0.0.jar` (23 MB)

### 2. Configuration Claude Code

Dans `.mcp.json` (à la racine de votre projet) :

```json
{
  "mcpServers": {
    "jdwp-inspector": {
      "command": "java",
      "args": [
        "-DHOME=C:/Users/nicolasv/MCP_servers/mcp-jdwp-java",
        "-DJVM_JDWP_PORT=61959",
        "-DDEBUGGERX_PROXY_PORT=55005",
        "-jar",
        "C:/Users/nicolasv/MCP_servers/mcp-jdwp-java/build/libs/mcp-jdwp-java-1.0.0.jar"
      ]
    }
  }
}
```

**Paramètres configurables :**
- `-DHOME` : Chemin vers le dossier mcp-jdwp-java (requis)
- `-DJVM_JDWP_PORT` : Port où la JVM écoute (défaut: 61959)
- `-DDEBUGGERX_PROXY_PORT` : Port du proxy debuggerX (défaut: 55005)

**Note:** La capture des exceptions (caught/uncaught) et les filtres se configurent dynamiquement via l'outil `jdwp_configure_exception_monitoring`.

### 3. Redémarrer Claude Code

Pour que la nouvelle config MCP soit prise en compte.

## Utilisation

### Étape 1: Lancer votre application Java en mode RUN avec JDWP activé

**Exemple avec Tomcat dans IntelliJ:**

Lancer en mode **RUN** (pas Debug) avec les VM Options suivantes:
```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:61959
```

L'application démarre normalement et écoute sur le port `JVM_JDWP_PORT=61959`.

### Étape 2: Connecter IntelliJ Remote Debug (optionnel)

Remote Debug Configuration sur `localhost:55005` (port `DEBUGGERX_PROXY_PORT`)

Cela permet de mettre des breakpoints via IntelliJ tout en inspectant simultanément via Claude Code.

**Note:** debuggerX (le proxy JDWP) se lance **automatiquement** lors de la première connexion.

### Étape 3: Utiliser le MCP JDWP Inspector dans Claude Code

```
Moi: "Connecte-toi à l'inspector"

Claude: jdwp_connect()
→ Lance debuggerX automatiquement si nécessaire
→ Se connecte au JDWP sur localhost:55005 (depuis la config .mcp.json)
→ Prêt à inspecter !

Moi: "J'ai un breakpoint actif, peux-tu analyser la requête?"

Claude:
1. Liste les threads
2. Trouve le thread suspendu
3. Inspecte la stack
4. Récupère les variables locales
5. Navigue dans les objets
6. Appelle des méthodes pour plus d'infos
7. Analyse le problème
```

**debuggerX permet à plusieurs debuggers (IntelliJ + MCP Inspector) de se connecter simultanément.**

Pour plus de détails sur le fonctionnement du routage multi-debuggers, voir [lib/debuggerX-README.md](lib/debuggerX-README.md).

## Outils MCP disponibles (30)

### 1. `jdwp_connect`
Se connecter au serveur JDWP en utilisant la configuration de `.mcp.json`.

**Paramètres:** Aucun (utilise automatiquement les ports configurés dans `.mcp.json`)

**Comportement:**
- Lit automatiquement `DEBUGGERX_PROXY_PORT` depuis les propriétés système
- Se connecte à `localhost` sur le port configuré (défaut: 55005)
- Lance automatiquement debuggerX si nécessaire

**Exemple:**
```
jdwp_connect()
```

### 2. `jdwp_disconnect`
Se déconnecter du serveur JDWP.

**Note:** Ne tue pas debuggerX, nettoie juste la référence locale.

### 3. `jdwp_get_version`
Obtenir les informations sur la JVM connectée.

**Retourne:**
```
VM: OpenJDK 64-Bit Server VM
Version: 11.0.21
Description: Java Debug Interface...
```

### 4. `jdwp_get_threads`
Lister tous les threads de la JVM avec leur statut.

**Retourne:** Pour chaque thread:
- ID unique
- Nom
- Statut (1=RUNNING, 4=WAITING, etc.)
- Suspendu (true/false)
- Nombre de frames (si suspendu)

**Exemple de sortie:**
```
Found 42 threads:

Thread 0:
  ID: 1
  Name: main
  Status: 1
  Suspended: true
  Frames: 14

Thread 14:
  ID: 15
  Name: http-nio-8080-exec-1
  Status: 1
  Suspended: true
  Frames: 93  ← Thread avec breakpoint actif
```

### 5. `jdwp_get_stack`
Obtenir la call stack complète d'un thread (doit être suspendu).

**Paramètres:**
- `threadId` (long) : ID du thread (obtenu via get_threads)

**Exemple:**
```
jdwp_get_stack(threadId=15)
```

**Retourne:**
```
Stack trace for thread 15 (http-nio-8080-exec-1) - 93 frames:

Frame 0:
  at com.axelor.web.service.RestService.find(RestService.java:186)
Frame 1:
  at com.axelor.web.service.RestService$$EnhancerByGuice...
...
```

### 6. `jdwp_get_locals`
Obtenir les variables locales d'une frame spécifique.

**Paramètres:**
- `threadId` (long) : ID du thread
- `frameIndex` (int) : Index de la frame (0 = frame courante, 1 = caller, etc.)

**Exemple:**
```
jdwp_get_locals(threadId=15, frameIndex=0)
```

**Retourne:**
```
Local variables in frame 0:

request (com.axelor.rpc.Request) = Object#26886 (com.axelor.rpc.Request)
```

Tous les objets sont automatiquement mis en cache pour inspection ultérieure.

### 7. `jdwp_get_fields`
Obtenir les champs d'un objet (ou les éléments d'une collection/array).

**Paramètres:**
- `objectId` (long) : ID de l'objet (obtenu via get_locals ou get_fields)

**Exemple:**
```
jdwp_get_fields(objectId=26886)  # request object
```

**Retourne pour un objet:**
```
Object #26886 (com.axelor.rpc.Request):

int limit = 40
int offset = 0
java.util.List sortBy = Object#26935 (java.util.ArrayList)
java.util.Map data = Object#26936 (java.util.LinkedHashMap)
...
```

**Retourne pour une ArrayList:**
```
Object #26935 (java.util.ArrayList):

Size: 1

Elements:
  [0] = "-invoiceDate"

--- Internal fields ---
...
```

**Retourne pour une LinkedHashMap:**
```
Object #26936 (java.util.LinkedHashMap):

Size: 5

Entries:
  "_domain" = "self.operationTypeSelect = 3"
  "_domainContext" = Object#26951 (LinkedHashMap)
  "operator" = "and"
  "criteria" = Object#26959 (ArrayList)
...
```

**Retourne pour un array:**
```
Array #26944 (java.lang.Object[]) - 10 elements:

[0] = "-invoiceDate"
[1] = null
[2] = null
...
```

**Collections supportées:**
- `ArrayList`, `LinkedList`
- `HashMap`, `LinkedHashMap`, `TreeMap`
- `HashSet`, `TreeSet`
- Arrays (Object[], int[], etc.)

### 9. `jdwp_resume`
Reprendre l'exécution de tous les threads dans la VM.

**Paramètres:** Aucun

**Exemple:**
```
jdwp_resume()
```

**Retourne:**
```
All threads resumed
```

**Note:** Resume tous les threads, équivalent à F8/Resume dans IntelliJ.


### 12. `jdwp_step_over`
Exécuter la ligne courante et s'arrêter à la ligne suivante (Step Over, équivalent F6).

**Paramètres:**
- `threadId` (long) : ID du thread (doit être suspendu)

**Exemple:**
```
jdwp_step_over(threadId=25)
```

**Retourne:**
```
Step over executed on thread 25 (http-nio-8080-exec-10)
```

**Note:** Le thread doit être suspendu. Crée une StepRequest et resume le thread.

### 13. `jdwp_step_into`
Entrer dans les appels de méthode (Step Into, équivalent F7).

**Paramètres:**
- `threadId` (long) : ID du thread (doit être suspendu)

**Exemple:**
```
jdwp_step_into(threadId=25)
```

**Retourne:**
```
Step into executed on thread 25 (http-nio-8080-exec-10)
```

### 14. `jdwp_step_out`
Sortir de la méthode courante (Step Out, équivalent Shift+F8).

**Paramètres:**
- `threadId` (long) : ID du thread (doit être suspendu)

**Exemple:**
```
jdwp_step_out(threadId=25)
```

**Retourne:**
```
Step out executed on thread 25 (http-nio-8080-exec-10)
```

### 15. `jdwp_set_breakpoint`
Placer un breakpoint à une ligne spécifique dans une classe.

**Paramètres:**
- `className` (String) : Nom complet de la classe (ex: "com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto")
- `lineNumber` (int) : Numéro de ligne

**Exemple:**
```
jdwp_set_breakpoint(
  className="com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto",
  lineNumber=82
)
```

**Retourne:**
```
Breakpoint set at com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto:82
```

**Note:** La classe doit être chargée et compilée avec les informations de debug (-g).

### 16. `jdwp_clear_breakpoint`
Retirer un breakpoint d'une ligne spécifique.

**Paramètres:**
- `className` (String) : Nom complet de la classe
- `lineNumber` (int) : Numéro de ligne

**Exemple:**
```
jdwp_clear_breakpoint(
  className="com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto",
  lineNumber=82
)
```

**Retourne:**
```
Removed 1 breakpoint(s) at com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto:82
```

### 17. `jdwp_list_breakpoints`
Lister tous les breakpoints actifs.

**Paramètres:** Aucun

**Exemple:**
```
jdwp_list_breakpoints()
```

**Retourne:**
```
Active breakpoints: 2

Breakpoint 1:
  Class: com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto
  Method: save
  Line: 82
  Enabled: true

Breakpoint 2:
  Class: com.axelor.meta.MetaFiles
  Method: attach
  Line: 597
  Enabled: true
```

### 18. `jdwp_get_events`
Obtenir les événements JDWP récents (breakpoints, steps, exceptions, etc.).

**Paramètres:**
- `count` (Integer, optionnel) : Nombre d'événements à récupérer (défaut: tous)

**Exemple:**
```
jdwp_get_events()           # Tous les événements
jdwp_get_events(count=10)   # Les 10 derniers
```

**Retourne:**
```
Recent JDWP events (10):

[21:45:32] BREAKPOINT: Thread 25 at com.axelor.apps.vpauto.repository.DMSFileRepositoryVPAuto.save:74
[21:45:28] STEP: Thread 25 at com.axelor.web.service.RestService.find:186
[21:45:20] BREAKPOINT: Thread 23 at com.axelor.meta.MetaFiles.attach:597
```

**Note:** L'event listener tourne en arrière-plan et capture **TOUS** les événements JDWP, y compris ceux déclenchés par IntelliJ ou d'autres debuggers connectés via debuggerX.

**Types d'événements capturés:**
- `BREAKPOINT` : Thread arrêté à un breakpoint
- `STEP` : Step over/into/out complété
- `EXCEPTION` : Exception levée
- `THREAD_START/DEATH` : Création/destruction de thread
- `CLASS_PREPARE` : Classe chargée
- `METHOD_ENTRY/EXIT` : Entrée/sortie de méthode (si configuré)

### 19. `jdwp_clear_events`
Vider l'historique des événements JDWP.

**Paramètres:** Aucun

**Exemple:**
```
jdwp_clear_events()
```

**Retourne:**
```
Event history cleared
```

**Note:** Utile pour nettoyer l'historique après une session de debug ou pour se concentrer sur de nouveaux événements.

### 20. `jdwp_get_current_thread`
Obtenir le thread ID du breakpoint actuel depuis le proxy.

**Paramètres:** Aucun

**Exemple:**
```
jdwp_get_current_thread()
```

**Retourne:**
```
Current thread: http-nio-8080-exec-6 (ID=26456, suspended=true, frames=93)
```

**Note:** Utilise l'API HTTP du proxy debuggerX pour récupérer automatiquement le thread du dernier breakpoint hit. Très utile avant d'appeler `jdwp_inspect_stack()`.

### 22. `jdwp_get_exception_config`
Obtenir la configuration actuelle de monitoring des exceptions.

**Paramètres:** Aucun

**Retourne:**
```
Exception monitoring configuration:
- Capture caught exceptions: true
- Include packages: com.axelor,org.myapp
- Exclude classes: java.lang.NumberFormatException
```

### 23. `jdwp_clear_all_breakpoints`
Supprimer TOUS les breakpoints de TOUS les clients (IntelliJ, MCP, etc.).

**Paramètres:** Aucun

**Avertissement:** Cette commande supprime également les breakpoints IntelliJ!

### 24. `jdwp_attach_watcher`
Attacher un watcher à un breakpoint pour évaluer une expression Java.

**Paramètres:**
- `breakpointId` (int) : ID du breakpoint (depuis `jdwp_list_breakpoints`)
- `label` (String) : Description du watcher
- `expression` (String) : Expression Java à évaluer (ex: `request.getData()`)

**Exemple:**
```
jdwp_attach_watcher(
  breakpointId=27,
  label="Trace request data",
  expression="request.getData()"
)
```

**Retourne:**
```
✓ Watcher attached successfully

  Watcher ID: 47e8090c-dc4a-4b03-a93a-068cd1b1e1ec
  Label: Trace request data
  Breakpoint: 27
  Expression: request.getData()
```

### 25. `jdwp_evaluate_watchers`
Évaluer les expressions des watchers attachés à un breakpoint.

**Paramètres:**
- `threadId` (long) : ID du thread suspendu
- `scope` (String) : `"current_frame"` ou `"full_stack"`
- `breakpointId` (Integer, optionnel) : ID du breakpoint pour optimisation

**Exemple:**
```
jdwp_evaluate_watchers(
  threadId=26162,
  scope="current_frame",
  breakpointId=27
)
```

**Retourne:**
```
=== Watcher Evaluation for Thread 26162 ===

─── Current Frame #0: RestService:192 (Breakpoint ID: 27) ───

  • [47e8090c] Trace request data
    request.getData() = Object#33761 (java.util.LinkedHashMap)

  • [82632e7d] Test string
    "Hello World" = "Hello World"

Total: Evaluated 2 expression(s)
```

**Format des résultats:**
- **Strings**: `"valeur"`
- **Primitives**: `42`, `true`
- **Objects**: `Object#ID (type)`

**Documentation complète**: Voir [EXPRESSION_EVALUATION.md](EXPRESSION_EVALUATION.md)

### 26. `jdwp_detach_watcher`
Détacher un watcher d'un breakpoint.

**Paramètres:**
- `watcherId` (String) : UUID du watcher (retourné par `jdwp_attach_watcher`)

**Exemple:**
```
jdwp_detach_watcher(watcherId="47e8090c-dc4a-4b03-a93a-068cd1b1e1ec")
```

### 27. `jdwp_list_watchers_for_breakpoint`
Lister tous les watchers attachés à un breakpoint spécifique.

**Paramètres:**
- `breakpointId` (int) : ID du breakpoint

### 28. `jdwp_list_all_watchers`
Lister tous les watchers actifs sur tous les breakpoints.

**Paramètres:** Aucun

**Retourne:**
```
Active watchers: 3

Breakpoint 27 (RestService:192) - 2 watcher(s):
  • [47e8090c] Trace request data
    Expression: request.getData()

  • [82632e7d] Test string
    Expression: "Hello World"

Breakpoint 29 (AuctionService:45) - 1 watcher(s):
  • [9f3c2a1b] Check auction status
    Expression: auction.getStatus()
```

### 29. `jdwp_clear_all_watchers`
Supprimer tous les watchers de tous les breakpoints.

**Paramètres:** Aucun

### 30. `jdwp_inspect_stack` 🚀
_(Déjà documenté ci-dessus comme outil #21)_

## Workflow typique

### Scénario 1: Debug d'une requête REST

```
1. Dans IntelliJ: Mettre un breakpoint dans RestService.find()

2. Dans le navigateur/Postman: Déclencher la requête

3. Dans Claude Code:
   "J'ai un breakpoint actif, peux-tu analyser la requête?"

4. Claude utilise automatiquement:
   - jdwp_connect() → connexion automatique avec config .mcp.json
   - jdwp_get_threads() → trouve thread 15 suspendu
   - jdwp_get_stack(15) → voit la stack complète
   - jdwp_get_locals(15, 0) → trouve request = Object#26886
   - jdwp_get_fields(26886) → voit request.data, request.limit, etc.
   - jdwp_get_fields(26936) → descend dans le LinkedHashMap

   "Le problème est que request.model est null alors que..."
```

### Scénario 2: Monitoring des breakpoints IntelliJ

```
1. Dans IntelliJ: Placer un breakpoint
2. Dans le navigateur: Déclencher une requête
3. IntelliJ s'arrête au breakpoint

4. Dans Claude Code:
   "Est-ce que tu as détecté le breakpoint?"

5. Claude utilise:
   - jdwp_get_events(count=5) → voit les derniers événements

   "[21:45:32] BREAKPOINT: Thread 25 at DMSFileRepositoryVPAuto.save:74"

   - jdwp_get_stack(25) → analyse la stack du thread arrêté
   - jdwp_get_locals(25, 0) → inspecte les variables

   "Oui, le thread 25 est arrêté à DMSFileRepositoryVPAuto.save:74
    Je vois que la variable 'key' contient..."
```

**Note:** L'event listener permet à Claude Code de "voir" ce qui se passe dans IntelliJ, créant une expérience de debug collaborative entre l'IDE et l'IA.

## Structure du projet

```
mcp-jdwp-java/
├── build.gradle                    # Config Gradle
├── settings.gradle
├── gradlew.bat                     # Gradle wrapper
├── start-debuggerx.bat             # Script pour lancer le proxy
├── .gitignore
├── README.md
├── lib/
│   ├── debuggerX.jar              # Proxy JDWP (8 MB)
│   └── debuggerX-README.md        # Documentation complète du proxy
├── src/main/
│   ├── java/io/mcp/jdwp/
│   │   ├── JDWPMcpServerApplication.java  # Main Spring Boot
│   │   ├── JDIConnectionService.java      # Service singleton
│   │   └── JDWPTools.java                 # 8 outils MCP
│   └── resources/
│       └── application.properties          # Config (stdout propre)
├── tests/                          # Scripts Python de test
│   ├── test_client.py
│   ├── test_collections.py
│   └── ...
└── build/
    └── libs/
        └── mcp-jdwp-java-1.0.0.jar        # JAR final (23 MB)
```

## Dépendances

- **Spring Boot 3.4.1** - Framework
- **Spring AI MCP 1.1.0-M3** - Intégration MCP
- **MCP Annotations 0.1.0** - @McpTool
- **JDI** (com.sun.jdi depuis tools.jar) - Interface de debug Java

## Avantages

✅ **vs implémentation Python:**
- Pas de parsing JDWP manuel
- API stable et documentée (JDI)
- Typage fort, moins d'erreurs
- Performance native Java

✅ **vs debugger classique:**
- Inspection automatisée par IA
- Navigation intelligente dans les objets
- Analyse contextuelle des problèmes
- Pas besoin de naviguer manuellement

## Troubleshooting

### "tools.jar not found"
Vérifiez que `JAVA_HOME` pointe vers un **JDK** (pas un JRE).

### "SocketAttach connector not found"
JDI n'est pas disponible. Utilisez un JDK avec tools.jar.

### Connection refused
- Vérifiez que Tomcat tourne avec `-agentlib:jdwp=...address=*:61959`
- Vérifiez les ports dans `.mcp.json` (`JVM_JDWP_PORT=61959`, `DEBUGGERX_PROXY_PORT=55005`)
- debuggerX se lance automatiquement lors de la connexion

### Le serveur MCP ne répond pas dans Claude Code
- Rebuild: `./gradlew.bat build`
- Vérifiez le chemin dans `.mcp.json`
- Redémarrez Claude Code

### "Thread is not suspended"
Un thread doit être arrêté à un breakpoint pour:
- `jdwp_get_stack`
- `jdwp_get_locals`
- `jdwp_invoke_method`

## Configuration personnalisée

### Changer les ports

**1. Modifier `.mcp.json`:**
```json
{
  "mcpServers": {
    "jdwp-inspector": {
      "command": "java",
      "args": [
        "-DHOME=C:/Users/nicolasv/MCP_servers/mcp-jdwp-java",
        "-DJVM_JDWP_PORT=12345",          // Port JVM personnalisé
        "-DDEBUGGERX_PROXY_PORT=54321",   // Port proxy personnalisé
        "-jar",
        "C:/Users/nicolasv/MCP_servers/mcp-jdwp-java/build/libs/mcp-jdwp-java-1.0.0.jar"
      ]
    }
  }
}
```

**2. Modifier les VM Options de l'application:**
```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:12345
```

**3. Redémarrer Claude Code pour recharger la configuration**

**4. Connecter les debuggers:**
- **IntelliJ**: Remote Debug sur `localhost:54321`
- **MCP Inspector**: Utilise automatiquement `jdwp_connect()` (lit la config)

Voir [lib/debuggerX-README.md](lib/debuggerX-README.md) pour plus de détails.

## Version

**1.2.0** - Version complète avec évaluation d'expressions Java
- **30 outils MCP** (8 inspection + 9 contrôle + 4 événements + 9 watchers)
- **Évaluation d'expressions (NEW):**
  - Compilation dynamique d'expressions Java au breakpoint
  - Découverte automatique du classpath (571 entrées)
  - Découverte automatique du JDK local
  - Support des proxies dynamiques (Guice, CGLIB)
  - Cache de compilation pour performance
  - 9 outils watchers (attach/evaluate/detach/list/clear)
- **Surveillance d'événements:**
  - Event listener en arrière-plan
  - Capture TOUS les événements JDWP (même depuis IntelliJ)
  - Historique des 100 derniers événements
  - Types: Breakpoints, Steps, Exceptions, Threads, etc.
- **Contrôle d'exécution:**
  - Resume/Suspend threads
  - Step Over/Into/Out
  - Set/Clear/List breakpoints
- **Inspection:**
  - Navigation récursive illimitée (Remote Inspector Pattern ~50x plus rapide)
  - Collections intelligentes
  - Invocation de méthodes
- Cache singleton persistant
- Lancement automatique de debuggerX
- Ports configurables via `.mcp.json`
- Connexion sans arguments (lit la config automatiquement)

## License

MIT
