package cz.libors.harness.foreign;

import java.util.HashMap;
import java.util.Map;

/**
 * The allowlist for foreign robot jars: which classes and members of the JDK and Robocode a robot may use.
 *
 * <p>A member is judged by its own rule if it has one, otherwise by its class's rule. A class is judged by its exact
 * rule, then its outer class's, then the longest package prefix; anything without a rule is denied. {@code FLAG}
 * marks what a robot may legitimately use but a reviewer should look at (threads, file access).
 */
public final class ApiRules {

    public enum Level { ALLOW, FLAG, DENY }

    public record Rule(Level level, String reason) {
    }

    static final Rule ALLOWED = new Rule(Level.ALLOW, "");
    private static final Rule UNKNOWN = new Rule(Level.DENY, "not on the allowlist");

    private static final String REFLECTION = "reflection";
    private static final String CLASS_LOADING = "class loading";
    private static final String PROCESSES = "processes";
    private static final String EXIT = "exit";
    private static final String ENVIRONMENT = "environment";
    private static final String NETWORK = "network";
    private static final String FILES = "file access";
    private static final String FILE_WRITES = "file writes";
    private static final String THREADS = "threads";
    private static final String JVM_STATE = "JVM-wide state";
    private static final String INTERNAL = "internal API";
    private static final String DESKTOP = "desktop";
    private static final String SERIALIZATION = "serialization";
    private static final String RESOURCES = "own resources";
    private static final String MANAGEMENT = "JVM management";
    private static final String ENGINE = "Robocode engine";

    /** Exact class names, and package prefixes ending in '/'. */
    private static final Map<String, Rule> TYPES = new HashMap<>();
    /** {@code owner.name}. */
    private static final Map<String, Rule> MEMBERS = new HashMap<>();

    static {
        allow("robocode/");
        deny(ENGINE, "robocode/control/", "net/sf/robocode/");

        allow("java/lang/Object", "java/lang/String", "java/lang/StringBuilder", "java/lang/StringBuffer",
                "java/lang/CharSequence", "java/lang/Math", "java/lang/StrictMath", "java/lang/Number",
                "java/lang/Integer", "java/lang/Long", "java/lang/Short", "java/lang/Byte", "java/lang/Double",
                "java/lang/Float", "java/lang/Character", "java/lang/Boolean", "java/lang/Void", "java/lang/Enum",
                "java/lang/Record", "java/lang/Iterable", "java/lang/Comparable", "java/lang/AutoCloseable",
                "java/lang/Cloneable", "java/lang/Runnable", "java/lang/Throwable", "java/lang/StackTraceElement",
                "java/lang/ThreadLocal", "java/lang/Appendable", "java/lang/Readable", "java/lang/ref/",
                "java/lang/annotation/", "java/lang/reflect/Array", "java/lang/runtime/ObjectMethods",
                "java/lang/runtime/SwitchBootstraps");
        allow("java/lang/System");
        denyMembers("java/lang/System", EXIT, "exit");
        denyMembers("java/lang/System", CLASS_LOADING, "load", "loadLibrary");
        denyMembers("java/lang/System", ENVIRONMENT, "getenv");
        // getProperties hands out the JVM's own Properties, which setProperty then changes.
        denyMembers("java/lang/System", JVM_STATE, "setIn", "setOut", "setErr", "setProperty", "getProperties",
                "setProperties", "clearProperty", "setSecurityManager", "inheritedChannel", "getLogger");
        deny(PROCESSES, "java/lang/Runtime", "java/lang/Process", "java/lang/ProcessBuilder",
                "java/lang/ProcessHandle");
        allowMembers("java/lang/Runtime", "getRuntime", "availableProcessors", "freeMemory", "maxMemory",
                "totalMemory", "gc", "version");
        flag(THREADS, "java/lang/Thread", "java/lang/ThreadGroup");
        allowMembers("java/lang/Thread", "currentThread", "sleep", "onSpinWait", "yield", "interrupted",
                "isInterrupted", "interrupt", "getName", "getId", "holdsLock", "getStackTrace");
        denyMembers("java/lang/Thread", CLASS_LOADING, "getContextClassLoader", "setContextClassLoader");
        denyMembers("java/lang/Thread", JVM_STATE, "setDefaultUncaughtExceptionHandler", "getAllStackTraces");
        deny(REFLECTION, "java/lang/Class", "java/lang/reflect/", "java/lang/invoke/", "java/beans/");
        allowMembers("java/lang/Class", "getName", "getSimpleName", "getTypeName", "getCanonicalName",
                "getPackageName", "desiredAssertionStatus", "isInstance", "cast", "isArray", "isPrimitive", "isEnum",
                "isRecord", "isInterface", "isAssignableFrom", "getComponentType", "componentType", "arrayType",
                "getSuperclass", "getEnumConstants", "hashCode", "equals", "toString");
        flagMembers("java/lang/Class", RESOURCES, "getResource", "getResourceAsStream");
        // The bootstraps javac emits for lambdas and string concatenation.
        allowMembers("java/lang/invoke/LambdaMetafactory", "metafactory", "altMetafactory");
        allowMembers("java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", "makeConcat");
        deny(CLASS_LOADING, "java/lang/ClassLoader", "java/lang/Module", "java/lang/ModuleLayer",
                "java/lang/instrument/", "java/util/ServiceLoader", "java/util/ResourceBundle", "java/util/spi/",
                "javax/script/", "javax/tools/", "java/sql/");
        deny(JVM_STATE, "java/lang/SecurityManager", "java/util/prefs/", "java/util/logging/");
        flag(MANAGEMENT, "java/lang/management/");
        deny(MANAGEMENT, "javax/management/");

        allow("java/util/", "java/math/", "java/text/", "java/time/");
        flag(THREADS, "java/util/concurrent/", "java/util/Timer", "java/util/TimerTask");
        allow("java/util/concurrent/ConcurrentHashMap", "java/util/concurrent/ConcurrentMap",
                "java/util/concurrent/ConcurrentLinkedQueue", "java/util/concurrent/ConcurrentLinkedDeque",
                "java/util/concurrent/ConcurrentSkipListMap", "java/util/concurrent/ConcurrentSkipListSet",
                "java/util/concurrent/CopyOnWriteArrayList", "java/util/concurrent/CopyOnWriteArraySet",
                "java/util/concurrent/ThreadLocalRandom", "java/util/concurrent/TimeUnit",
                "java/util/concurrent/atomic/", "java/util/concurrent/locks/");
        flag(FILES, "java/util/jar/");

        allow("java/io/");
        flag(FILES, "java/io/File", "java/io/FileInputStream", "java/io/FileReader");
        deny(FILE_WRITES, "java/io/FileOutputStream", "java/io/FileWriter", "java/io/RandomAccessFile",
                "java/io/FileDescriptor");
        flag(SERIALIZATION, "java/io/ObjectInputStream", "java/io/ObjectOutputStream");
        allow("java/nio/");
        deny(FILES, "java/nio/file/", "java/nio/channels/");

        allow("java/awt/geom/", "java/awt/event/", "java/awt/Color", "java/awt/Graphics", "java/awt/Graphics2D",
                "java/awt/BasicStroke", "java/awt/Stroke", "java/awt/Font", "java/awt/FontMetrics",
                "java/awt/Point", "java/awt/Rectangle", "java/awt/Shape", "java/awt/Polygon",
                "java/awt/RenderingHints", "java/awt/AlphaComposite", "java/awt/Composite", "java/awt/Dimension",
                "java/awt/Paint", "java/awt/GradientPaint");
        deny(DESKTOP, "java/awt/", "javax/swing/");

        deny(NETWORK, "java/net/", "javax/net/", "java/rmi/", "javax/naming/");
        deny("security", "java/security/", "javax/security/", "javax/crypto/");
        deny(INTERNAL, "sun/", "jdk/", "com/sun/");
    }

    private ApiRules() {
    }

    /** The rule for naming a class: extending or implementing it, and the default for its members. */
    public static Rule type(String internalName) {
        String name = internalName;
        while (name.startsWith("[")) {
            name = name.substring(1);
        }
        if (name.length() == 1) {
            return ALLOWED; // primitive array
        }
        if (name.startsWith("L") && name.endsWith(";")) {
            name = name.substring(1, name.length() - 1);
        }
        Rule exact = TYPES.get(name);
        if (exact != null) {
            return exact;
        }
        int nested = name.indexOf('$');
        if (nested > 0 && TYPES.containsKey(name.substring(0, nested))) {
            return TYPES.get(name.substring(0, nested));
        }
        if (isJavaLangThrowable(name)) {
            return ALLOWED;
        }
        for (int slash = name.lastIndexOf('/'); slash > 0; slash = name.lastIndexOf('/', slash - 1)) {
            Rule rule = TYPES.get(name.substring(0, slash + 1));
            if (rule != null) {
                return rule;
            }
        }
        return UNKNOWN;
    }

    /** The rule for using a field or method of another class. */
    public static Rule member(String owner, String name, String descriptor) {
        if (owner.startsWith("[")) {
            return ALLOWED; // clone() and length of arrays
        }
        if (name.equals("<init>") && (descriptor.startsWith("(Ljava/lang/String;")
                || descriptor.startsWith("(Ljava/io/File;"))) {
            // These constructors open the named file.
            if (owner.equals("java/io/PrintStream") || owner.equals("java/io/PrintWriter")
                    || owner.equals("java/util/Formatter")) {
                return new Rule(Level.DENY, FILE_WRITES);
            }
        }
        if ((owner.equals("java/util/Scanner") || owner.equals("java/util/zip/ZipFile")) && name.equals("<init>")
                && (descriptor.startsWith("(Ljava/io/File;") || descriptor.startsWith("(Ljava/nio/file/Path;")
                || owner.equals("java/util/zip/ZipFile") && descriptor.startsWith("(Ljava/lang/String;"))) {
            return new Rule(Level.FLAG, FILES); // these constructors open the named file
        }
        if (name.startsWith("parallel") && owner.startsWith("java/util/")) {
            return new Rule(Level.FLAG, THREADS); // parallel streams and sorts run on the common pool
        }
        Rule rule = MEMBERS.get(owner + "." + name);
        return rule != null ? rule : type(owner);
    }

    private static boolean isJavaLangThrowable(String name) {
        if (!name.startsWith("java/lang/") || name.indexOf('/', "java/lang/".length()) >= 0) {
            return false;
        }
        return name.endsWith("Exception") || name.endsWith("Error");
    }

    private static void allow(String... names) {
        for (String name : names) {
            TYPES.put(name, ALLOWED);
        }
    }

    private static void flag(String reason, String... names) {
        for (String name : names) {
            TYPES.put(name, new Rule(Level.FLAG, reason));
        }
    }

    private static void deny(String reason, String... names) {
        for (String name : names) {
            TYPES.put(name, new Rule(Level.DENY, reason));
        }
    }

    private static void allowMembers(String owner, String... names) {
        for (String name : names) {
            MEMBERS.put(owner + "." + name, ALLOWED);
        }
    }

    private static void flagMembers(String owner, String reason, String... names) {
        for (String name : names) {
            MEMBERS.put(owner + "." + name, new Rule(Level.FLAG, reason));
        }
    }

    private static void denyMembers(String owner, String reason, String... names) {
        for (String name : names) {
            MEMBERS.put(owner + "." + name, new Rule(Level.DENY, reason));
        }
    }
}
