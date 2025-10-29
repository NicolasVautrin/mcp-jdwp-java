# MCP JDWP Inspector

Serveur MCP (Model Context Protocol) pour inspecter des applications Java en temps réel via JDWP en utilisant JDI (Java Debug Interface).

Permet à Claude Code d'inspecter l'état d'une JVM pendant l'exécution, comme un debugger.

## Architecture

```
Claude Code
    ↓ (MCP Protocol via STDIO)
Spring Boot MCP Server (8 tools)
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

✅ **Scripting**
- Invocation de méthodes sur les objets (comme dans un debugger)
- Appel de getters/accesseurs
- Résultats typés

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

Dans `~/.mcp.json` :

```json
{
  "mcpServers": {
    "jdwp-inspector": {
      "command": "java",
      "args": [
        "-jar",
        "C:/Users/nicolasv/MCP_servers/mcp-jdwp-java/build/libs/mcp-jdwp-java-1.0.0.jar"
      ]
    }
  }
}
```

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

### Étape 2: Lancer debuggerX (proxy JDWP)

```bash
cd mcp-jdwp-java
start-debuggerx.bat
```

Paramètres par défaut:
- `JVM_JDWP_PORT=61959` - Se connecte à la JVM
- `DEBUGGERX_PROXY_PORT=55005` - Écoute pour les debuggers

**debuggerX permet à plusieurs debuggers (IntelliJ + MCP Inspector) de se connecter simultanément.**

Pour plus de détails sur le fonctionnement du routage multi-debuggers, voir [lib/debuggerX-README.md](lib/debuggerX-README.md).

### Étape 3: Connecter IntelliJ Remote Debug (optionnel)

Remote Debug Configuration sur `localhost:55005` (port `DEBUGGERX_PROXY_PORT`)

Cela permet de mettre des breakpoints via IntelliJ tout en inspectant simultanément via Claude Code.

### Étape 4: Utiliser le MCP JDWP Inspector dans Claude Code

```
Moi: "Mets un breakpoint dans RestService.find() et déclenche la requête"

Claude:
1. Se connecte au JDWP
2. Liste les threads
3. Trouve le thread suspendu
4. Inspecte la stack
5. Récupère les variables locales
6. Navigue dans les objets
7. Appelle des méthodes pour plus d'infos
8. Analyse le problème
```

## Outils MCP disponibles (8)

### 1. `jdwp_connect`
Se connecter au serveur JDWP.

**Paramètres:**
- `host` (String) : hostname (ex: "localhost")
- `port` (int) : port du proxy debuggerX (ex: 55005)

**Exemple:**
```
jdwp_connect(host="localhost", port=55005)
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

### 8. `jdwp_invoke_method`
Invoquer une méthode sur un objet (scripting comme dans un debugger).

**Paramètres:**
- `threadId` (long) : ID du thread (doit être suspendu)
- `objectId` (long) : ID de l'objet
- `methodName` (String) : Nom de la méthode (ex: "toString", "getModel")

**Exemple:**
```
jdwp_invoke_method(threadId=15, objectId=26886, methodName="toString")
jdwp_invoke_method(threadId=15, objectId=26886, methodName="getLimit")
jdwp_invoke_method(threadId=15, objectId=26886, methodName="getData")
```

**Retourne:**
```
Result: "com.axelor.rpc.Request@121dda"
Type: java.lang.String
```

**Note:** La méthode est exécutée dans le contexte du thread suspendu. Le résultat est automatiquement mis en cache s'il s'agit d'un objet.

## Workflow typique

### Scénario: Debug d'une requête REST

```
1. Dans IntelliJ: Mettre un breakpoint dans RestService.find()

2. Dans le navigateur/Postman: Déclencher la requête

3. Dans Claude Code:
   "J'ai un breakpoint actif, peux-tu analyser la requête?"

4. Claude utilise automatiquement:
   - jdwp_connect(localhost, 55005)
   - jdwp_get_threads() → trouve thread 15 suspendu
   - jdwp_get_stack(15) → voit la stack complète
   - jdwp_get_locals(15, 0) → trouve request = Object#26886
   - jdwp_get_fields(26886) → voit request.data, request.limit, etc.
   - jdwp_get_fields(26936) → descend dans le LinkedHashMap
   - jdwp_invoke_method(15, 26886, "getModel") → vérifie le model

   "Le problème est que request.model est null alors que..."
```

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
- Vérifiez que debuggerX est lancé (`start-debuggerx.bat`)
- Vérifiez que Tomcat tourne avec `-agentlib:jdwp=...address=*:61959`
- Vérifiez les ports (`JVM_JDWP_PORT=61959`, `DEBUGGERX_PROXY_PORT=55005`)

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

**1. Modifier `start-debuggerx.bat`:**
```bat
set JVM_JDWP_PORT=12345          REM Port où la JVM écoute
set DEBUGGERX_PROXY_PORT=54321   REM Port où les debuggers se connectent
```

**2. Modifier les VM Options de l'application:**
```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:12345
```

**3. Connecter les debuggers sur le nouveau port proxy:**
- **IntelliJ**: Remote Debug sur `localhost:54321`
- **MCP Inspector**: `jdwp_connect(host="localhost", port=54321)`

Voir [lib/debuggerX-README.md](lib/debuggerX-README.md) pour plus de détails.

## Version

**1.0.0** - Version complète avec Spring Boot + JDI
- 8 outils MCP
- Navigation récursive illimitée
- Collections intelligentes
- Invocation de méthodes
- Cache singleton persistant

## License

MIT
