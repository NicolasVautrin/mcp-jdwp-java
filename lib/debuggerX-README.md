# debuggerX - JDWP Multi-Debugger Proxy

## Qu'est-ce que debuggerX ?

**debuggerX** est un proxy JDWP (Java Debug Wire Protocol) open-source qui permet à plusieurs debuggers de se connecter simultanément à la même JVM.

- **Source**: https://github.com/ouoou/debuggerX
- **Version incluse**: `debuggerX.jar` (8 MB)
- **License**: Open Source

## Pourquoi utiliser debuggerX ?

### Cas d'usage

1. **Debug collaboratif**: Plusieurs développeurs peuvent débugger la même application en même temps
2. **Outils multiples**: Utiliser IntelliJ ET le MCP JDWP Inspector simultanément
3. **Proxy de sécurité**: Exposer un port proxy au lieu du port JDWP de production

### Sans debuggerX

```
JVM:61959 <-- [UN SEUL debugger à la fois]
```

Si IntelliJ est connecté, le MCP Inspector ne peut pas se connecter.

### Avec debuggerX

```
JVM:61959 <-- debuggerX:55005 <-- [IntelliJ + MCP Inspector + autres debuggers]
```

Tous les debuggers peuvent se connecter simultanément au port 55005.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Application Java (Tomcat, Spring Boot, etc.)              │
│  VM Options: -agentlib:jdwp=...address=*:61959             │
└───────────────────────┬─────────────────────────────────────┘
                        │ JVM JDWP Port: 61959
                        │
          ┌─────────────▼──────────────┐
          │      debuggerX Proxy        │
          │   Intelligent Routing       │
          │   Session Management        │
          └─────────────┬───────────────┘
                        │ Proxy Port: 55005
                        │
        ┌───────────────┼───────────────┐
        │               │               │
   ┌────▼────┐    ┌────▼────┐    ┌────▼─────┐
   │IntelliJ │    │   MCP   │    │  Autre   │
   │  Debug  │    │Inspector│    │ Debugger │
   └─────────┘    └─────────┘    └──────────┘
```

## Comment fonctionne le routage ?

debuggerX implémente un **routage intelligent par ID de packet JDWP** :

### 1. Tracking des requêtes

Chaque requête JDWP envoyée par un debugger se voit assigner un nouvel ID interne :

```
IntelliJ envoie : Request ID=42
debuggerX track : 42 -> IntelliJ
debuggerX envoie JVM : Request ID=100
```

### 2. Routage des réponses

Les réponses de la JVM sont renvoyées au debugger d'origine :

```
JVM envoie : Response ID=100
debuggerX lookup : 100 -> Request 42 -> IntelliJ
debuggerX envoie : Response ID=42 -> IntelliJ
```

### 3. Broadcast des events

Les events JDWP (breakpoints, exceptions) sont broadcast à tous les debuggers concernés :

```
JVM envoie : Event "Breakpoint hit"
debuggerX broadcast -> IntelliJ + MCP Inspector
```

### 4. Session management

- Une **session JVM** peut avoir N debuggers attachés
- Si la JVM se déconnecte → toute la session est fermée
- Si un debugger se déconnecte → seul ce debugger est retiré

## Configuration des ports

### Port 1 : JVM_JDWP_PORT (défaut: 61959)

Port où la JVM écoute les connexions JDWP.

**Configuration dans IntelliJ/Tomcat** :
```
VM Options:
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:61959
```

### Port 2 : DEBUGGERX_PROXY_PORT (défaut: 55005)

Port unique où **tous les debuggers** se connectent.

**Configuration dans `start-debuggerx.bat`** :
```bat
set JVM_JDWP_PORT=61959
set DEBUGGERX_PROXY_PORT=55005
```

## Lancement

### 1. Lancer debuggerX

```bat
cd mcp-jdwp-java
start-debuggerx.bat
```

Sortie attendue :
```
Starting DebuggerX proxy...
JVM JDWP Port (target):  61959
Proxy Port (debuggers):  55005

Multiple debuggers can connect to port 55005
debuggerX will route packets intelligently based on request IDs
```

### 2. Vérifier la connexion à la JVM

debuggerX se connecte automatiquement au port 61959 quand la JVM démarre.

Logs debuggerX :
```
[DecodeHandShake] Handshake completed for channel: ... R:localhost/127.0.0.1:61959
```

### 3. Connecter les debuggers

**IntelliJ Remote Debug** :
- Host: `localhost`
- Port: `55005`

**MCP JDWP Inspector** :
```
jdwp_connect(host="localhost", port=55005)
```

## Troubleshooting

### Connection refused sur le port 55005

**Cause** : debuggerX n'est pas lancé

**Solution** :
```bat
start-debuggerx.bat
```

### "No available jvm server session found"

**Cause** : La JVM n'est pas connectée à debuggerX

**Solution** : Vérifier que :
1. L'application Java tourne avec `-agentlib:jdwp=...address=*:61959`
2. Le port 61959 est bien celui configuré dans `start-debuggerx.bat`

### Erreur "Connection reset"

**Cause** : Un debugger a envoyé une commande `Dispose` qui ferme la connexion

**Solution** : Dans JDI (MCP Inspector), ne jamais appeler `vm.dispose()`, juste `vm = null`

### Changer les ports

**1. Modifier `start-debuggerx.bat`** :
```bat
set JVM_JDWP_PORT=12345
set DEBUGGERX_PROXY_PORT=54321
```

**2. Modifier les VM Options de l'application** :
```
-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:12345
```

**3. Connecter les debuggers sur le nouveau port proxy** :
- IntelliJ : `localhost:54321`
- MCP Inspector : `jdwp_connect("localhost", 54321)`

## Architecture interne

### Modules debuggerX

- **debuggerx-common** : Utilitaires et constantes
- **debuggerx-protocol** : Implémentation JDWP
- **debuggerx-core** : Logique métier et gestion des sessions
- **debuggerx-transport** : Couche réseau (Netty)
- **debuggerx-bootstrap** : Démarrage et configuration

### Technologie

- **Netty** : Framework réseau asynchrone
- **JDWP** : Java Debug Wire Protocol (standard JVM)
- **Java 11+** : Runtime

## Avantages

✅ **Debug collaboratif** - Plusieurs développeurs sur la même instance
✅ **Multi-outils** - IntelliJ + MCP Inspector + autres en même temps
✅ **Production-safe** - Pas besoin d'exposer le port JDWP directement
✅ **Routage intelligent** - Chaque requête va au bon debugger
✅ **Gestion propre** - Déconnexion d'un debugger n'affecte pas les autres
✅ **Open source** - Code disponible sur GitHub

## Références

- **GitHub** : https://github.com/ouoou/debuggerX
- **JDWP Spec** : https://docs.oracle.com/javase/8/docs/technotes/guides/jpda/jdwp-spec.html
- **Netty** : https://netty.io
