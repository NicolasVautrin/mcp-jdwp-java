# Workflow de Développement - MCP JDWP Inspector

## Architecture de la Stack

```
Claude Code (MCP Client)
    ↓
MCP Server (mcp-jdwp-java) - Build: Gradle
    ↓ Lance automatiquement
debuggerX Proxy - Build: Maven
    ↓
JVM JDWP (Application Tomcat)
```

**Composants:**
- **MCP Server**: `C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\` (Gradle, Spring Boot)
- **Proxy debuggerX**: `C:\Users\nicolasv\MCP_servers\debuggerX\` (Maven, Netty)
- **JAR du proxy**: Doit être copié dans `C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\lib\debuggerX.jar`

## ⚠️ RÈGLE CRITIQUE AVANT TOUTE MODIFICATION

**AVANT de commencer à coder, TOUJOURS suivre ces étapes :**

### Étape 1 : Désactiver le serveur MCP (TOUJOURS)
1. **Demander à l'utilisateur** de désactiver le serveur MCP via `/mcp`
2. Attendre confirmation avant de continuer

### Étape 2 : Arrêter le Proxy (SI modification de debuggerX uniquement)
**🚨 Si et seulement si tu modifies le proxy debuggerX :**

**Arrêt propre (OBLIGATOIRE) :**
```bash
# 1. Arrêter proprement
curl -X POST http://localhost:55006/shutdown

# 2. Vérifier que c'est arrêté
netstat -ano | findstr :55005
# Doit retourner "Exit code 1" (aucun processus trouvé)
```

**Si le shutdown échoue (FALLBACK uniquement) :**
```bash
netstat -ano | findstr :55005
# Noter le PID, puis utiliser PowerShell pour le tuer
powershell -Command "Stop-Process -Id <PID> -Force"
```

### Étape 3 : Coder
Maintenant tu peux commencer à modifier le code

### Pourquoi c'est critique ?
- Le serveur MCP cache le JAR en mémoire
- Le proxy peut tourner avec l'ancien code
- Les modifications ne seront PAS visibles sans redémarrage
- Cela évite des heures de debugging inutile

**RAPPEL : TOUJOURS désactiver le MCP, tuer le proxy seulement si nécessaire**

## Problèmes Récurrents

### ❌ Problème #1: Oublier d'arrêter le proxy avant rebuild
**Symptôme**: Le proxy tourne avec l'ancien code après rebuild

**Solution**: 🚨 **CRITIQUE** - TOUJOURS arrêter le proxy AVANT de rebuilder debuggerX

**Méthode recommandée :**
```bash
curl -X POST http://localhost:55006/shutdown
netstat -ano | findstr :55005  # Vérifier qu'il est arrêté
```

**Pourquoi c'est critique ?**
- Le JAR ne peut pas être remplacé si le processus l'utilise
- Le proxy continue avec l'ancien code
- Les modifications ne sont JAMAIS visibles
- Perte de temps énorme en debugging

### ❌ Problème #2: Oublier de copier le JAR du proxy
**Symptôme**: Le serveur MCP démarre l'ancien proxy même après rebuild

**Solution**: Toujours copier `debuggerX.jar` vers `mcp-jdwp-java\lib\`

### ❌ Problème #3: Oublier de désactiver/réactiver le serveur MCP
**Symptôme**: Les modifications du serveur MCP ne sont pas prises en compte

**Solution**: Utiliser `/mcp` dans Claude Code pour recharger la config

### ❌ Problème #4: Build Maven qui tourne en boucle
**Symptôme**: Maven ne termine jamais, CPU à 100%

**Solution**: Utiliser `-DskipTests` pour éviter les tests qui peuvent bloquer

### ❌ Problème #5: Port 55005 déjà utilisé
**Symptôme**: Le proxy ne démarre pas car le port est occupé

**Solution**: Tuer le processus qui écoute sur ce port

### ❌ Problème #6: gradlew.bat ne s'exécute pas avec cmd
**Symptôme**: `cmd /c gradlew.bat build` ne produit aucune sortie ou échoue

**Solution**: Utiliser PowerShell au lieu de cmd

```bash
# ❌ Ne fonctionne pas toujours
cmd /c "cd /d C:\Users\nicolasv\MCP_servers\mcp-jdwp-java && gradlew.bat build"

# ✅ Fonctionne avec PowerShell
powershell -Command "cd C:\Users\nicolasv\MCP_servers\mcp-jdwp-java; .\gradlew.bat build"
```

**Quand utiliser PowerShell:**
- Lors du build de modules Gradle (mcp-inspector-agent, mcp-jdwp-java)
- Quand vous avez besoin de capturer la sortie du build
- Pour utiliser des commandes comme `Select-Object`, `Get-Item`, etc.

**Note:** Maven (mvn.cmd) fonctionne directement depuis bash avec le chemin complet, contrairement à Gradle qui nécessite PowerShell. Cette différence vient du wrapper gradlew.bat qui a des problèmes de redirection avec bash.

### ❌ Problème #7: Faire les étapes manuellement au lieu d'utiliser le script
**Symptôme**: Builder le proxy manuellement (Maven, copy, etc.) au lieu d'utiliser `build-and-copy.bat`

**Solution**: ⚠️ **TOUJOURS utiliser le script automatique** pour rebuilder le proxy debuggerX

```bash
# ✅ CORRECT - Utiliser le script avec PowerShell
powershell -Command "& 'C:\Users\nicolasv\MCP_servers\debuggerX\build-and-copy.bat'"

# ❌ INCORRECT - Utiliser cmd /c (ne capture pas la sortie correctement depuis bash)
# cmd /c "C:\Users\nicolasv\MCP_servers\debuggerX\build-and-copy.bat"

# ❌ INCORRECT - Faire les étapes manuellement (erreurs possibles)
# Ne PAS faire: mvn clean package puis copy puis...
```

**Pourquoi ?**
- Le script automatise build + copy de manière fiable (plus besoin de kill manuel grâce à /shutdown)
- Évite d'oublier une étape (comme copier le JAR)
- Gère correctement les chemins Windows et encodage
- **PowerShell est requis pour capturer la sortie depuis bash**
- **Le script existe pour une raison - TOUJOURS l'utiliser !**

## Workflow Complet

### Scénario 1: Modifier le Proxy debuggerX

**✅ CHECKLIST OBLIGATOIRE POUR MODIFICATION DU PROXY :**

- [ ] Désactiver le serveur MCP (`/mcp` dans Claude Code)
- [ ] **Arrêter le proxy avec shutdown** (`curl -X POST http://localhost:55006/shutdown`)
- [ ] **Vérifier que le proxy est arrêté** (`netstat -ano | findstr :55005` → doit échouer)
- [ ] Builder avec le script automatique
- [ ] Réactiver le MCP et tester

**⚠️ WORKFLOW COMPLET (recommandé) :**

```bash
# ÉTAPE 1: Arrêter proprement le proxy (CRITIQUE - NE PAS OUBLIER!)
curl -X POST http://localhost:55006/shutdown

# ÉTAPE 2: Vérifier que le proxy est arrêté
netstat -ano | findstr :55005
# Doit retourner "Exit code 1" (aucun résultat)

# ÉTAPE 3: Builder avec le script automatique
powershell -Command "& 'C:\Users\nicolasv\MCP_servers\debuggerX\build-and-copy.bat'"

# Le script fait automatiquement:
# 1. Build Maven avec -DskipTests
# 2. Copie le JAR dans lib/

# ÉTAPE 4: Redémarrer le serveur MCP dans Claude Code
# Dans Claude Code: /mcp (réactiver)
# Dans Claude Code: "Connecte-toi" → jdwp_connect()
```

**Méthode manuelle (NON recommandée - utiliser seulement si le script échoue) :**

```bash
# Étape 1: Tuer le proxy actuel (CRITIQUE!)
netstat -ano | findstr :55005
# Noter le PID (dernière colonne)
powershell -Command "Stop-Process -Id <PID> -Force"

# Étape 2: Builder le proxy
cd C:\Users\nicolasv\MCP_servers\debuggerX
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd" clean package -DskipTests

# Étape 3: Vérifier que le build a réussi
# Chercher "BUILD SUCCESS" dans la sortie
powershell -Command "Get-Item C:\Users\nicolasv\MCP_servers\debuggerX\debuggerx-bootstrap\target\debuggerx-bootstrap-1.0-SNAPSHOT.jar | Select-Object Name, Length, LastWriteTime"

# Étape 4: Copier le JAR dans lib/ du serveur MCP
powershell -Command "Copy-Item 'C:\Users\nicolasv\MCP_servers\debuggerX\debuggerx-bootstrap\target\debuggerx-bootstrap-1.0-SNAPSHOT.jar' -Destination 'C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\lib\debuggerX.jar' -Force"

# Étape 5: Redémarrer le serveur MCP dans Claude Code
# Dans Claude Code: /mcp (désactiver)
# Dans Claude Code: /mcp (réactiver)
# Dans Claude Code: "Connecte-toi" → jdwp_connect()
```

### Scénario 2: Modifier le Serveur MCP

```bash
# Étape 1: Builder le serveur MCP (avec PowerShell recommandé)
powershell -Command "cd C:\Users\nicolasv\MCP_servers\mcp-jdwp-java; .\gradlew.bat build"

# Étape 2: Vérifier que le build a réussi
# Chercher "BUILD SUCCESSFUL" dans la sortie
powershell -Command "Get-Item C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\build\libs\mcp-jdwp-java-1.0.0.jar | Select-Object Name, Length, LastWriteTime"

# Étape 3: Redémarrer le serveur MCP dans Claude Code
# Dans Claude Code: /mcp (désactiver)
# Dans Claude Code: /mcp (réactiver)
# Le nouveau JAR sera chargé automatiquement
```

### Scénario 3: Modifier les deux (Proxy + MCP)

```bash
# Étape 1: Tuer le proxy
netstat -ano | findstr :55005
powershell -Command "Stop-Process -Id <PID> -Force"

# Étape 2: Builder le proxy
cd C:\Users\nicolasv\MCP_servers\debuggerX
& "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd" clean package -DskipTests

# Étape 3: Copier le JAR du proxy
copy C:\Users\nicolasv\MCP_servers\debuggerX\debuggerx-bootstrap\target\debuggerx-bootstrap-1.0-SNAPSHOT.jar C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\lib\debuggerX.jar

# Étape 4: Builder le serveur MCP (avec PowerShell recommandé)
powershell -Command "cd C:\Users\nicolasv\MCP_servers\mcp-jdwp-java; .\gradlew.bat build"

# Étape 5: Redémarrer dans Claude Code
# Dans Claude Code: /mcp (désactiver puis réactiver)
```

### Scénario 4: Builder l'Inspector Agent

```bash
# Builder uniquement le module mcp-inspector-agent
powershell -Command "cd C:\Users\nicolasv\MCP_servers\mcp-jdwp-java; .\gradlew.bat :mcp-inspector-agent:build"

# Vérifier que Inspector.class a été généré
powershell -Command "Get-Item C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\mcp-inspector-agent\build\classes\java\main\io\mcp\inspector\Inspector.class | Select-Object Name, Length"

# Note: Inspector.class sera automatiquement copié dans src/main/resources/bytecode/
# lors du prochain build du projet principal (grâce à la tâche copyInspectorBytecode)
```

## Commandes de Diagnostic

### Vérifier si le proxy tourne
```bash
netstat -ano | findstr :55005
# Si une ligne avec LISTENING apparaît → proxy actif
```

### Vérifier quand le proxy a démarré
```bash
# Trouver le PID
netstat -ano | findstr :55005
# Le PID est dans la dernière colonne (ex: 88660)

# Voir l'heure de démarrage
powershell -Command "Get-Process -Id 88660 | Select-Object Id, ProcessName, StartTime"
```

### Trouver le PID du proxy
```bash
# Méthode 1: Par port
netstat -ano | findstr :55005
# Le PID est dans la dernière colonne

# Méthode 2: Par ligne de commande
wmic process where "CommandLine like '%debuggerX.jar%'" get ProcessId,CommandLine
```

### Tuer le proxy
```bash
# ⚠️ TOUJOURS utiliser PowerShell (taskkill a des problèmes d'encodage avec bash)
powershell -Command "Stop-Process -Id <PID> -Force"
```

### Vérifier la date des JARs
```bash
# Proxy JAR dans lib/
powershell -Command "Get-Item C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\lib\debuggerX.jar | Select-Object Name, Length, LastWriteTime"

# Proxy JAR source
powershell -Command "Get-Item C:\Users\nicolasv\MCP_servers\debuggerX\debuggerx-bootstrap\target\debuggerx-bootstrap-1.0-SNAPSHOT.jar | Select-Object Name, Length, LastWriteTime"

# Serveur MCP JAR
powershell -Command "Get-Item C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\build\libs\mcp-jdwp-java-1.0.0.jar | Select-Object Name, Length, LastWriteTime"
```

### Vérifier les logs

Tous les fichiers de logs sont dans le répertoire du serveur MCP : `C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\`

```bash
# ⭐ LOGS DU SERVEUR MCP (SLF4J - PRINCIPAL)
# Contient les logs de JDIConnectionService, InMemoryJavaCompiler, JdiExpressionEvaluator
type C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\mcp-jdwp-inspector.log

# Logs du proxy debuggerX (connexion, événements JDWP)
type C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\debuggerx-proxy.log
```

**📍 Emplacement des logs MCP : `C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\mcp-jdwp-inspector.log`**

**Contenu des logs MCP :**
- Messages de découverte du classpath (`[JDI] Discovering classpath...`)
- Compilation d'expressions (`[Compiler] Compilation successful...`)
- Évaluation d'expressions (`[Evaluator] Compiler classpath configured...`)
- Toutes les exceptions avec stack traces complètes

### Tester l'API HTTP du proxy
```bash
# Le serveur HTTP démarre sur proxyPort + 1 = 55006
curl http://localhost:55006/breakpoints
```

## Utilisation des Outils MCP JDWP

### Workflow d'Inspection de Stack

**IMPORTANT**: Toujours utiliser `jdwp_get_current_thread()` avant `jdwp_inspect_stack()` pour inspecter le thread au breakpoint actuel :

```
1. Déclencher un breakpoint dans l'application
2. Appeler jdwp_get_current_thread() pour obtenir le threadId
3. Utiliser le threadId retourné pour appeler jdwp_inspect_stack(threadId)
```

**Exemple d'utilisation correcte :**
```
User: [Déclenche un breakpoint]
Assistant: jdwp_get_current_thread()
Result: { "threadId": 26872, "threadName": "http-nio-8080-exec-10" }
Assistant: jdwp_inspect_stack(26872)
```

**Pourquoi cette approche ?**
- `jdwp_get_current_thread()` récupère automatiquement le thread du breakpoint actuel via le proxy
- Permet d'inspecter plusieurs threads en passant différents threadId
- Évite de deviner le threadId manuellement

### Outils Disponibles

#### Connexion et Navigation
- **jdwp_connect** - Se connecter à la JVM cible
- **jdwp_get_current_thread** - Obtenir le threadId du breakpoint actuel
- **jdwp_get_threads** - Lister tous les threads (utile pour inspecter d'autres threads)

#### Inspection
- **jdwp_inspect_stack(threadId)** - Inspecter la stack complète d'un thread
- **jdwp_get_stack(threadId)** - Obtenir les frames de la stack (méthode classique JDWP)
- **jdwp_get_locals(threadId, frameIndex)** - Obtenir les variables locales d'une frame
- **jdwp_get_fields(objectId)** - Obtenir les champs d'un objet

#### Breakpoints et Exécution
- **jdwp_set_breakpoint(className, lineNumber)** - Poser un breakpoint
- **jdwp_list_breakpoints** - Lister tous les breakpoints
- **jdwp_clear_breakpoint_by_id(requestId)** - Supprimer un breakpoint
- **jdwp_resume** - Reprendre l'exécution

#### Évaluation d'Expressions (Watchers)
- **jdwp_attach_watcher(breakpointId, label, expression)** - Attacher un watcher à un breakpoint
- **jdwp_evaluate_watchers(threadId, scope, breakpointId)** - Évaluer les expressions des watchers
- **jdwp_list_all_watchers()** - Lister tous les watchers actifs
- **jdwp_detach_watcher(watcherId)** - Détacher un watcher

### Évaluation d'Expressions

Le serveur MCP permet d'évaluer des expressions Java arbitraires dans le contexte d'un thread suspendu à un breakpoint.

**Documentation complète**: Voir [EXPRESSION_EVALUATION.md](EXPRESSION_EVALUATION.md)

**Workflow typique**:
```
1. Déclencher un breakpoint
2. Obtenir le threadId: jdwp_get_current_thread()
3. Attacher un watcher: jdwp_attach_watcher(breakpointId=27, label="Test", expression="request.getData()")
4. Évaluer: jdwp_evaluate_watchers(threadId=26162, scope="current_frame", breakpointId=27)
```

**Exemples d'expressions**:
```java
// Strings
"Hello World"                    → "Hello World"

// Primitives
42 + 10                          → 52

// Variables locales
request.getData()                → Object#33761 (java.util.LinkedHashMap)

// Navigation
request.getData().size()         → 5

// Utilisation de 'this'
this.getClass().getName()        → "com.axelor.web.service.RestService"
```

**Points clés**:
- ✅ Compile avec le classpath complet de l'application (571 entrées)
- ✅ Résout automatiquement les proxies (Guice, CGLIB)
- ✅ Cache les compilations pour performance
- ✅ Gère strings, primitives, objets et arrays
- ⚠️ Nécessite que le thread soit suspendu à un breakpoint

## Checklist Avant de Tester

- [ ] Le proxy a été tué (vérifier avec `netstat -ano | findstr :55005`)
- [ ] Le proxy a été buildé avec succès ("BUILD SUCCESS" dans Maven)
- [ ] Le JAR du proxy a été copié dans `lib/` (vérifier la date de modification)
- [ ] Le serveur MCP a été buildé (si modifié) ("BUILD SUCCESSFUL" dans Gradle)
- [ ] Le serveur MCP a été redémarré dans Claude Code (`/mcp` désactiver + réactiver)
- [ ] La JVM cible tourne avec JDWP sur port 61959 (`netstat -ano | findstr :61959`)

## Structure des Répertoires

```
C:\Users\nicolasv\MCP_servers\
├── mcp-jdwp-java\              # Serveur MCP (Gradle)
│   ├── build\
│   │   └── libs\
│   │       └── mcp-jdwp-java-1.0.0.jar  # JAR du serveur MCP
│   ├── lib\
│   │   └── debuggerX.jar       # ⚠️ JAR du proxy (COPIÉ DEPUIS debuggerX/)
│   ├── src\
│   ├── build.gradle
│   ├── gradlew.bat
│   ├── README.md
│   ├── WORKFLOW.md             # Ce fichier
│   └── debuggerx-proxy.log     # Logs du proxy
│
└── debuggerX\                  # Proxy JDWP (Maven)
    ├── debuggerx-bootstrap\
    │   └── target\
    │       └── debuggerx-bootstrap-1.0-SNAPSHOT.jar   # JAR source du proxy
    ├── debuggerx-core\
    ├── debuggerx-protocol\
    ├── pom.xml
    └── README.md
```

## Ports Utilisés

| Port  | Service                    | Vérification                     |
|-------|----------------------------|----------------------------------|
| 61959 | JVM JDWP                   | `netstat -ano \| findstr :61959` |
| 55005 | Proxy debuggerX            | `netstat -ano \| findstr :55005` |
| 55006 | HTTP API du proxy          | `curl http://localhost:55006/breakpoints` |

## Scripts Utiles

### Script de Kill Automatique du Proxy
Créer `kill-proxy.bat`:
```batch
@echo off
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :55005 ^| findstr LISTENING') do (
    echo Killing proxy process %%a
    powershell -Command "Stop-Process -Id %%a -Force"
)
```

### Script de Build Complet
Créer `build-and-copy.bat`:
```batch
@echo off
echo === Killing proxy ===
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :55005 ^| findstr LISTENING') do (
    powershell -Command "Stop-Process -Id %%a -Force"
)

echo === Building proxy ===
cd C:\Users\nicolasv\MCP_servers\debuggerX
call "C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd" clean package -DskipTests

echo === Copying JAR ===
copy debuggerx-bootstrap\target\debuggerx-bootstrap-1.0-SNAPSHOT.jar ..\mcp-jdwp-java\lib\debuggerX.jar

echo === Done ===
echo Next: Restart MCP server in Claude Code (/mcp)
```

## Notes Importantes

1. **Le proxy se lance automatiquement**: Quand vous faites `jdwp_connect`, le serveur MCP vérifie si le proxy tourne et le démarre si nécessaire.

2. **Le serveur MCP charge le JAR au démarrage**: Les modifications du JAR ne sont prises en compte qu'après un redémarrage du serveur MCP via `/mcp`.

3. **Les modifications de code nécessitent un rebuild**: Java ne permet pas le hot-reload pour ce type d'application.

4. **Toujours vérifier les logs**: En cas de problème, consulter `debuggerx-proxy.log` dans le répertoire du serveur MCP.

5. **Port HTTP incorrect dans l'ancienne doc**: Le port HTTP est `proxyPort + 1 = 55006`, PAS 8765.

6. **Présentation de code à l'utilisateur**: Toujours utiliser des blocs de code markdown (```java ... ```) pour afficher du code. Ne JAMAIS utiliser la sortie brute d'outils comme Read ou Grep pour présenter du code à l'utilisateur.

7. **Gestion des exceptions**: TOUJOURS utiliser un logger (SLF4J) dans TOUS les blocs catch pour tracer les exceptions dans les fichiers de logs. Ne JAMAIS se contenter de retourner un message d'erreur sans logger l'exception.

```java
// ❌ INCORRECT - Retourne l'erreur mais ne la trace pas dans les logs
} catch (Exception e) {
    return "ERROR: " + e.getMessage();
}

// ✅ CORRECT - Logger l'exception avant de retourner l'erreur
} catch (Exception e) {
    log.error("[JDWP] Description de l'erreur", e);
    return "ERROR: " + e.getMessage();
}
```

**Pourquoi c'est critique ?**
- Les exceptions doivent être tracées dans les fichiers de logs pour le diagnostic
- Les messages retournés à l'utilisateur sont éphémères
- Les logs persistent et permettent l'analyse post-mortem
- Le logger SLF4J inclut automatiquement la stack trace complète

## Dépannage

### Le proxy ne démarre pas
```bash
# Vérifier que le port est libre
netstat -ano | findstr :55005

# Vérifier le JAR existe
dir C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\lib\debuggerX.jar

# Consulter les logs
type C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\debuggerx-proxy.log
```

### Les modifications ne sont pas prises en compte
```bash
# Vérifier la date du JAR
powershell -Command "Get-Item C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\lib\debuggerX.jar | Select-Object LastWriteTime"

# Comparer avec le JAR source
powershell -Command "Get-Item C:\Users\nicolasv\MCP_servers\debuggerX\debuggerx-bootstrap\target\debuggerx-bootstrap-1.0-SNAPSHOT.jar | Select-Object LastWriteTime"

# Si les dates diffèrent → re-copier
copy C:\Users\nicolasv\MCP_servers\debuggerX\debuggerx-bootstrap\target\debuggerx-bootstrap-1.0-SNAPSHOT.jar C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\lib\debuggerX.jar
```

### Build Maven bloqué
```bash
# Trouver le PID du processus Maven
tasklist | findstr mvn

# Tuer le processus avec PowerShell
powershell -Command "Stop-Process -Name 'mvn' -Force"

# Relancer avec skip tests et PowerShell
powershell -Command "cd C:\Users\nicolasv\MCP_servers\debuggerX; & 'C:\Program Files\JetBrains\IntelliJ IDEA 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd' clean package -DskipTests"
```
