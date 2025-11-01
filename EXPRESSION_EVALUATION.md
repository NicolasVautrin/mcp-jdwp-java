# Évaluation d'Expressions Java via JDWP

## Vue d'ensemble

Le serveur MCP JDWP permet d'évaluer des expressions Java arbitraires dans le contexte d'un thread suspendu à un breakpoint. Cette fonctionnalité utilise **JDI (Java Debug Interface)** pour compiler, injecter et exécuter du code dynamiquement dans la JVM cible.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ 1. Utilisateur attache un watcher à un breakpoint          │
│    Expression: "request.getData()"                          │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│ 2. Breakpoint déclenché → Thread suspendu                  │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│ 3. JdiExpressionEvaluator.evaluate()                        │
│    • Analyse le stack frame (variables locales + 'this')    │
│    • Génère code wrapper avec UUID unique                   │
│    • Compile avec EclipseCompiler + classpath découvert     │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│ 4. RemoteCodeExecutor.execute()                             │
│    • Injecte bytecode via ClassLoader.defineClass()         │
│    • Force initialisation avec Class.forName()              │
│    • Invoque méthode statique evaluate()                    │
└─────────────────┬───────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────┐
│ 5. Résultat renvoyé et formaté                              │
│    • Strings: "valeur"                                      │
│    • Primitives: 42, true                                   │
│    • Objects: Object#12345 (java.util.HashMap)              │
└─────────────────────────────────────────────────────────────┘
```

## Composants Principaux

### 1. JdiExpressionEvaluator

**Rôle**: Orchestrateur principal de l'évaluation d'expressions.

**Fichier**: `src/main/java/io/mcp/jdwp/evaluation/JdiExpressionEvaluator.java`

**Responsabilités**:
- Extraire le contexte d'exécution (variables locales, `this`)
- Générer le code source du wrapper
- Gérer la compilation (avec cache)
- Déléguer l'exécution à `RemoteCodeExecutor`

**Exemple de code généré**:
```java
package mcp.jdi.evaluation;

// UUID unique pour éviter les collisions de noms de classe
public class ExpressionEvaluator_a1b2c3d4e5f6... {
    public static Object evaluate(RestService _this, Request request) {
        // Expression utilisateur
        return (Object) (request.getData());
    }
}
```

**Points clés**:
- **UUID dans le nom de classe**: Évite `LinkageError` lors du rechargement du serveur MCP
- **Remplacement de `this`**: L'expression utilisateur utilise `this`, mais le paramètre est `_this`
- **Cache de compilation**: Évite de recompiler la même expression

### 2. InMemoryJavaCompiler

**Rôle**: Compile le code Java en bytecode sans écrire sur disque.

**Fichier**: `src/main/java/io/mcp/jdwp/evaluation/InMemoryJavaCompiler.java`

**Compilateur utilisé**: **Eclipse JDT Core Compiler** (pas besoin de JDK en runtime)

**Configuration**:
- **JDK path**: Découvert dynamiquement via `JdkDiscoveryService`
- **Classpath**: 571 entrées découvertes via exploration des classloaders (Tomcat)
- **Options**: `-source 1.8 -target 1.8 -g --system <jdkPath>`

**Pourquoi des fichiers temporaires?**
Le compilateur Eclipse JDT nécessite des fichiers réels (pas in-memory) pour lire le source via `JavaFileObject`. Les fichiers sont créés dans `/tmp/mcp-compiler-*` et nettoyés automatiquement.

**Option `--system`**:
Permet au compilateur de résoudre les classes JDK (`java.lang.Object`, etc.) en pointant vers le JDK local correspondant à la version de la JVM cible.

### 3. RemoteCodeExecutor

**Rôle**: Injecte et exécute le bytecode compilé dans la JVM cible.

**Fichier**: `src/main/java/io/mcp/jdwp/evaluation/RemoteCodeExecutor.java`

**Étapes d'exécution**:

#### Étape 1: Injection du bytecode
```java
ClassLoader.defineClass(String name, byte[] b, int off, int len)
```
Charge la classe dans le classloader cible, mais **ne la prépare pas encore**.

#### Étape 2: Initialisation forcée ⚠️ CRITIQUE
```java
Class.forName(className, true, classLoader)
```

**Pourquoi c'est nécessaire?**
- `defineClass()` charge les octets mais ne déclenche pas la préparation de la classe
- Sans préparation, `methodsByName()` lève `ClassNotPreparedException`
- `Class.forName()` force la JVM à préparer et initialiser la classe

**Cette solution a été identifiée après avoir testé:**
- ❌ `allMethods()` → Accès à toutes les méthodes héritées (inefficace)
- ✅ `Class.forName()` → Force la préparation (solution standard et robuste)

#### Étape 3: Invocation de la méthode
```java
ClassType.invokeMethod(thread, method, args, INVOKE_SINGLE_THREADED)
```

**Flag `INVOKE_SINGLE_THREADED`**:
- Nécessite que le thread soit suspendu à un "safepoint" (breakpoint)
- Évite les problèmes de concurrence dans la VM cible

### 4. ClasspathDiscoverer

**Rôle**: Découvre l'intégralité du classpath de l'application (JARs chargés dynamiquement).

**Fichier**: `src/main/java/io/mcp/jdwp/evaluation/ClasspathDiscoverer.java`

**Pourquoi c'est nécessaire?**
Dans Tomcat, `System.getProperty("java.class.path")` retourne seulement 2 JARs (bootstrap). Les 500+ JARs de l'application sont chargés dynamiquement via `WebappClassLoader`.

**Méthode d'exploration**:
```java
// 1. Récupérer le context classloader du thread
ClassLoaderReference contextCL = thread.getContextClassLoader();

// 2. Remonter la hiérarchie des classloaders
while (currentCL != null) {
    if (currentCL instanceof URLClassLoader) {
        // 3. Extraire les URLs via getURLs()
        URL[] urls = currentCL.getURLs();
        // Ajouter au classpath
    }
    currentCL = currentCL.getParent();
}
```

**Résultat**: 571 entrées de classpath découvertes
- 535 JARs de `ParallelWebappClassLoader` (Tomcat)
- 34 JARs de `URLClassLoader` (système)
- 2 entrées initiales de `java.class.path`

### 5. JdkDiscoveryService

**Rôle**: Trouve le JDK local correspondant à la version de la JVM cible.

**Fichier**: `src/main/java/io/mcp/jdwp/evaluation/JdkDiscoveryService.java`

**Étapes de découverte**:
1. Récupérer `java.version` et `java.home` de la JVM cible via JDI
2. Vérifier si `java.home` est accessible localement (même machine)
3. Chercher dans les emplacements standards:
   - `C:\Program Files\Eclipse Adoptium\jdk-*`
   - `C:\Program Files\Java\jdk-*`
   - Variable d'environnement `JAVA_HOME`

**Validation du JDK**:
- Java 9+: Présence du répertoire `jmods/` ou `lib/jrt-fs.jar`
- Java 8: Présence de `lib/rt.jar`

**En cas d'échec**:
Lève une `JdkNotFoundException` avec instructions d'installation.

## Problèmes Résolus

### Problème 1: LinkageError sur les noms de classe

**Symptôme**:
```
java.lang.LinkageError: duplicate class definition: mcp.jdi.evaluation.ExpressionEvaluator_0
```

**Cause**:
Le compteur de classe (`AtomicLong`) repart à 0 à chaque redémarrage du serveur MCP, mais les classes précédentes restent dans la JVM cible.

**Solution**:
Utiliser un **UUID** au lieu d'un compteur:
```java
String uniqueId = UUID.randomUUID().toString().replace("-", "");
String className = "mcp.jdi.evaluation.ExpressionEvaluator_" + uniqueId;
```

### Problème 2: ClassNotPreparedException

**Symptôme**:
```
com.sun.jdi.ClassNotPreparedException
    at ReferenceTypeImpl.methodsByName(ReferenceTypeImpl.java:570)
```

**Cause**:
`defineClass()` charge le bytecode mais ne prépare pas la classe. La méthode `methodsByName()` nécessite que la classe soit dans l'état "prepared".

**Solution**:
Forcer l'initialisation avec `Class.forName()`:
```java
ClassType classClass = (ClassType) vm.classesByName("java.lang.Class").get(0);
Method forNameMethod = classClass.methodsByName(
    "forName",
    "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;"
).get(0);

StringReference classNameRef = vm.mirrorOf(className);
BooleanValue initializeRef = vm.mirrorOf(true);
List<Value> args = List.of(classNameRef, initializeRef, classLoader);

classClass.invokeMethod(thread, forNameMethod, args, INVOKE_SINGLE_THREADED);
```

Cette approche est **robuste** car elle s'appuie sur le mécanisme standard de la JVM pour le cycle de vie des classes.

### Problème 3: Proxies dynamiques (Guice, CGLIB)

**Symptôme**:
```
Compilation failed: RestService$EnhancerByGuice$110706492 cannot be resolved to a type
```

**Cause**:
`thisObject.type().name()` retourne le nom du proxy au runtime (`RestService$EnhancerByGuice$...`), pas la classe déclarée.

**Solution**:
Extraire la classe de base en remontant la hiérarchie de classes:
```java
private String getDeclaredType(ReferenceType type) {
    String typeName = type.name();

    // Détecter les proxies (contiennent $$)
    if (typeName.contains("$$")) {
        if (type instanceof ClassType) {
            ClassType superclass = ((ClassType) type).superclass();
            if (superclass != null && !superclass.name().equals("java.lang.Object")) {
                return getDeclaredType(superclass); // Récursif
            }
        }

        // Fallback: extraire le nom avant $$
        return typeName.substring(0, typeName.indexOf("$$"));
    }

    return typeName;
}
```

### Problème 4: Résolution des classes JDK

**Symptôme**:
```
The type java.lang.Object cannot be resolved
```

**Cause**:
Le compilateur ne trouve pas les classes JDK de base.

**Solution**:
Option `--system <jdkPath>` du compilateur Eclipse:
```java
options.addAll(Arrays.asList("--system", this.jdkPath));
```

Pointe vers le JDK local découvert dynamiquement, permettant au compilateur de résoudre toutes les classes système.

## Utilisation des Watchers

### Attacher un watcher

```bash
jdwp_attach_watcher(
    breakpointId=27,
    label="Test request data",
    expression="request.getData()"
)
```

**Paramètres**:
- `breakpointId`: ID du breakpoint (obtenu via `jdwp_list_breakpoints`)
- `label`: Description lisible du watcher
- `expression`: Expression Java à évaluer

### Évaluer les watchers

```bash
jdwp_evaluate_watchers(
    threadId=26162,
    scope="current_frame",
    breakpointId=27  # Optionnel mais recommandé pour les performances
)
```

**Paramètres**:
- `threadId`: ID du thread (obtenu via `jdwp_get_current_thread`)
- `scope`:
  - `"current_frame"`: Évalue seulement dans la frame actuelle
  - `"full_stack"`: Cherche le breakpoint dans toute la stack
- `breakpointId`: Optimisation pour éviter de parcourir toute la stack

### Format des résultats

**Strings**:
```
"Hello World" = "Hello World"
```

**Primitives**:
```
42 = 42
true = true
```

**Objects**:
```
request.getData() = Object#33761 (java.util.LinkedHashMap)
```

L'objet est mis en cache et peut être inspecté avec `jdwp_get_fields(33761)`.

**Arrays**:
```
items = Array#12345 (java.lang.String[10])
```

## Contraintes et Limitations

### 1. Thread doit être suspendu

L'évaluation nécessite un thread suspendu à un breakpoint car:
- `INVOKE_SINGLE_THREADED` requiert un "safepoint"
- Les variables locales ne sont accessibles que quand le thread est arrêté

### 2. Pas d'appels JDI imbriqués

**Critique**: Ne jamais appeler `discoverClasspath()` ou d'autres méthodes JDI pendant une évaluation.

**Pourquoi?**
Les invocations JDI peuvent déclencher d'autres appels JDI, causant des deadlocks ou des `IncompatibleThreadStateException`.

**Solution**:
Configurer le classpath **avant** l'évaluation:
```java
expressionEvaluator.configureCompilerClasspath(suspendedThread);
Value result = expressionEvaluator.evaluate(frame, expression);
```

### 3. Expressions limitées au contexte

Les expressions ont accès uniquement à:
- Variables locales visibles dans la frame
- `this` (si disponible)
- Classes du classpath découvert

**Pas d'accès à**:
- Variables d'autres threads
- Méthodes privées via réflexion (possible mais non implémenté)

### 4. Pas de side-effects persistants

Les classes injectées sont chargées dans le classloader cible mais:
- Existent seulement le temps de l'évaluation
- Ne peuvent pas modifier l'état de l'application de façon permanente
- Les initialiseurs statiques (`<clinit>`) sont exécutés une seule fois

## Performance

### Temps d'évaluation typique

**Première évaluation** (avec découverte classpath):
```
JDK discovery:        ~140ms
Classpath discovery:  ~850ms
Compilation:          ~1900ms
Injection + exécution: ~750ms
──────────────────────────────
Total:                ~3640ms
```

**Évaluations suivantes** (cache activé):
```
Compilation (cache hit): ~0ms
Injection + exécution:   ~750ms
─────────────────────────────
Total:                   ~750ms
```

### Optimisations

**Cache de compilation**:
Clé de cache = `signature du contexte + expression`
```java
String cacheKey = context.getSignature() + "###" + expression;
// Signature = "RestService _this,Request request"
```

**Pas de recompilation** si:
- Même expression
- Même types de paramètres
- Même ordre de paramètres

**Invalide le cache** si:
- Expression différente
- Types de paramètres différents (ex: proxy vs classe de base)

## Fichiers de Log

Tous les logs sont dans `C:\Users\nicolasv\MCP_servers\mcp-jdwp-java\mcp-jdwp-inspector.log`

### Logs utiles pour le debugging

**Découverte du classpath**:
```
[JDI] Discovering full classpath using breakpoint thread 'http-nio-8080-exec-3'
[JDK Discovery] ✓ Found matching JDK: C:\Program Files\Eclipse Adoptium\jdk-11.0.21.9-hotspot
[Discoverer] Application classpath discovered in 629ms (571 entries)
```

**Compilation**:
```
[Compiler] Configured with JDK at C:\...\jdk-11.0.21.9-hotspot and 571 classpath entries
[Compiler] Compilation successful in 1934ms (736 bytes generated for 1 class(es))
```

**Exécution**:
```
[Executor] Loading class mcp.jdi.evaluation.ExpressionEvaluator_a1b2c3d4...
[Executor] Forcing initialization of class mcp.jdi.evaluation.ExpressionEvaluator_a1b2c3d4...
[Executor] Class initialization completed
[Executor] Found static method evaluate
[Executor] Remote method invoked successfully in 756ms, returned: java.util.LinkedHashMap
```

## Tests et Validation

### Test basique

```java
// Expression simple
"Hello World"
// Résultat attendu: "Hello World"

// Primitive
42 + 10
// Résultat attendu: 52

// Variable locale
request
// Résultat attendu: Object#12345 (com.axelor.rpc.Request)
```

### Test avec méthodes

```java
// Appel de méthode
request.getData()
// Résultat attendu: Object#33761 (java.util.LinkedHashMap)

// Navigation
request.getData().size()
// Résultat attendu: 5
```

### Test avec 'this'

```java
// Utilisation de 'this'
this.getClass().getName()
// Résultat attendu: "com.axelor.web.service.RestService"
```

## Références

- [JDI Specification](https://docs.oracle.com/en/java/javase/11/docs/api/jdk.jdi/com/sun/jdi/package-summary.html)
- [Eclipse JDT Core Compiler](https://www.eclipse.org/jdt/core/)
- [Java Class Loading](https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-5.html)
- [JDWP Protocol](https://docs.oracle.com/en/java/javase/11/docs/specs/jdwp/jdwp-protocol.html)
