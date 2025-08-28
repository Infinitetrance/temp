package com.example.si.umlgen;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.javapoet.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import org.springframework.integration.graph.GraphNode;
import org.springframework.integration.graph.IntegrationGraph;
import org.springframework.integration.graph.IntegrationGraphServer;
import org.springframework.integration.graph.LinkNode;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SI UML Generator
 * -----------------
 * A single-file, production-ready utility that reads a Spring Integration graph (from XML context or Actuator)
 * and emits a package of Java classes representing the flow. You can then open these classes in IntelliJ IDEA
 * (Ultimate) and use "Diagrams → Show Diagram" to view a clean, yFiles-powered diagram.
 *
 * Key features:
 *  - Two mapping modes: ASSOCIATION (edges as fields) and INHERITANCE (single-outgoing becomes extends)
 *  - Option to collapse channels (render channel name on edge, skip separate channel nodes)
 *  - Marker interfaces per SI type (lets you color/stereotype nodes in IntelliJ UML)
 *  - Robust naming strategy for valid, stable class names
 *  - Works with either a running Boot app's /actuator/integrationgraph or raw XML context(s)
 *
 * Typical usage (XML):
 *  SiUmlGenerator.run(
 *      SiUmlGenerator.Config.xmlBuilder()
 *          .withXmlLocations(List.of("integration/main-context.xml"))
 *          .withOutputDir(Path.of("target/generated-sources/si-uml"))
 *          .withBasePackage("com.example.si.diagram")
 *          .withMode(Config.Mode.ASSOCIATION)
 *          .withCollapseChannels(true)
 *          .build()
 *  );
 *
 * Typical usage (Actuator):
 *  SiUmlGenerator.run(
 *      SiUmlGenerator.Config.actuatorBuilder()
 *          .withActuatorBaseUri(URI.create("http://localhost:8080"))
 *          .withOutputDir(Path.of("target/generated-sources/si-uml"))
 *          .withBasePackage("com.example.si.diagram" )
 *          .withMode(Config.Mode.ASSOCIATION)
 *          .withCollapseChannels(true)
 *          .build()
 *  );
 */
public final class SiUmlGenerator {
  private static final Logger log = LoggerFactory.getLogger(SiUmlGenerator.class);

  private SiUmlGenerator() {}

  // =====================================================================================
  //  Public API
  // =====================================================================================

  public static void run(Config cfg) {
    Objects.requireNonNull(cfg, "cfg");
    long t0 = System.nanoTime();

    GraphProvider provider = cfg.source == Config.Source.XML
        ? new SpringContextGraphProvider(cfg.xmlLocations)
        : new ActuatorGraphProvider(cfg.actuatorBaseUri);

    GraphModel model = provider.loadGraph();
    log.info("Loaded graph: nodes={}, links={}", model.nodes.size(), model.links.size());

    GraphModel processed = cfg.collapseChannels ? GraphOps.collapseChannels(model) : model;
    if (cfg.nodeTypeIncludes != null && !cfg.nodeTypeIncludes.isEmpty()) {
      processed = GraphOps.filterByNodeTypes(processed, cfg.nodeTypeIncludes);
    }

    UmlEmitter emitter = new UmlEmitter(cfg);
    emitter.emit(processed);

    long dt = (System.nanoTime() - t0) / 1_000_000;
    log.info("Generation complete in {} ms → {}", dt, cfg.outputDir.toAbsolutePath());
  }

  // =====================================================================================
  //  Config
  // =====================================================================================

  public static final class Config {
    public enum Mode { ASSOCIATION, INHERITANCE }
    public enum Source { XML, ACTUATOR }

    // Required
    final Source source;
    final Path outputDir;
    final String basePackage;
    final Mode mode;

    // Source-specific
    final List<String> xmlLocations;            // for XML
    final URI actuatorBaseUri;                  // for Actuator

    // Options
    final boolean collapseChannels;             // replace channel nodes with edge labels
    final boolean generateMarkerInterfaces;     // for IntelliJ UML styling by type
    final Set<String> nodeTypeIncludes;         // if non-empty, only include these SI node types

    private Config(Source source, Path outputDir, String basePackage, Mode mode,
                   List<String> xmlLocations, URI actuatorBaseUri,
                   boolean collapseChannels, boolean generateMarkerInterfaces,
                   Set<String> nodeTypeIncludes) {
      this.source = source;
      this.outputDir = Objects.requireNonNull(outputDir);
      this.basePackage = Objects.requireNonNull(basePackage);
      this.mode = Objects.requireNonNull(mode);
      this.xmlLocations = xmlLocations == null ? List.of() : List.copyOf(xmlLocations);
      this.actuatorBaseUri = actuatorBaseUri;
      this.collapseChannels = collapseChannels;
      this.generateMarkerInterfaces = generateMarkerInterfaces;
      this.nodeTypeIncludes = nodeTypeIncludes == null ? Set.of() : Set.copyOf(nodeTypeIncludes);
    }

    public static XmlBuilder xmlBuilder() { return new XmlBuilder(); }
    public static ActuatorBuilder actuatorBuilder() { return new ActuatorBuilder(); }

    public static final class XmlBuilder {
      private Path outputDir;
      private String basePackage;
      private Mode mode = Mode.ASSOCIATION;
      private List<String> xmlLocations = new ArrayList<>();
      private boolean collapseChannels = true;
      private boolean generateMarkerInterfaces = true;
      private Set<String> nodeTypeIncludes = Set.of();

      public XmlBuilder withOutputDir(Path p) { this.outputDir = p; return this; }
      public XmlBuilder withBasePackage(String p) { this.basePackage = p; return this; }
      public XmlBuilder withMode(Mode m) { this.mode = m; return this; }
      public XmlBuilder withXmlLocations(List<String> locs) { this.xmlLocations = new ArrayList<>(locs); return this; }
      public XmlBuilder withCollapseChannels(boolean b) { this.collapseChannels = b; return this; }
      public XmlBuilder withGenerateMarkerInterfaces(boolean b) { this.generateMarkerInterfaces = b; return this; }
      public XmlBuilder withNodeTypeIncludes(Set<String> s) { this.nodeTypeIncludes = s; return this; }
      public Config build() {
        require(outputDir != null, "outputDir required");
        require(basePackage != null && !basePackage.isBlank(), "basePackage required");
        require(!xmlLocations.isEmpty(), "xmlLocations required");
        return new Config(Source.XML, outputDir, basePackage, mode, xmlLocations, null,
            collapseChannels, generateMarkerInterfaces, nodeTypeIncludes);
      }
    }

    public static final class ActuatorBuilder {
      private Path outputDir;
      private String basePackage;
      private Mode mode = Mode.ASSOCIATION;
      private URI actuatorBaseUri;
      private boolean collapseChannels = true;
      private boolean generateMarkerInterfaces = true;
      private Set<String> nodeTypeIncludes = Set.of();

      public ActuatorBuilder withOutputDir(Path p) { this.outputDir = p; return this; }
      public ActuatorBuilder withBasePackage(String p) { this.basePackage = p; return this; }
      public ActuatorBuilder withMode(Mode m) { this.mode = m; return this; }
      public ActuatorBuilder withActuatorBaseUri(URI uri) { this.actuatorBaseUri = uri; return this; }
      public ActuatorBuilder withCollapseChannels(boolean b) { this.collapseChannels = b; return this; }
      public ActuatorBuilder withGenerateMarkerInterfaces(boolean b) { this.generateMarkerInterfaces = b; return this; }
      public ActuatorBuilder withNodeTypeIncludes(Set<String> s) { this.nodeTypeIncludes = s; return this; }
      public Config build() {
        require(outputDir != null, "outputDir required");
        require(basePackage != null && !basePackage.isBlank(), "basePackage required");
        require(actuatorBaseUri != null, "actuatorBaseUri required");
        return new Config(Source.ACTUATOR, outputDir, basePackage, mode, null, actuatorBaseUri,
            collapseChannels, generateMarkerInterfaces, nodeTypeIncludes);
      }
    }

    private static void require(boolean cond, String msg) {
      if (!cond) throw new IllegalArgumentException(msg);
    }
  }

  // =====================================================================================
  //  Graph model + operations
  // =====================================================================================

  static final class GraphModel {
    final List<Node> nodes;        // index by id with map() when needed
    final List<Link> links;
    GraphModel(List<Node> nodes, List<Link> links) {
      this.nodes = List.copyOf(nodes);
      this.links = List.copyOf(links);
    }
    Map<String, Node> nodeMap() { return nodes.stream().collect(Collectors.toMap(n -> n.id, Function.identity())); }
  }

  static final class Node {
    final String id;
    final String name;
    final String type; // e.g., "transformer", "router", "channel", "service-activator"
    Node(String id, String name, String type) {
      this.id = Objects.requireNonNull(id);
      this.name = name == null ? "" : name;
      this.type = type == null ? "" : type;
    }
  }

  static final class Link {
    final String from;
    final String to;
    final String channelId; // may be null/empty
    Link(String from, String to, String channelId) {
      this.from = Objects.requireNonNull(from);
      this.to = Objects.requireNonNull(to);
      this.channelId = channelId;
    }
  }

  static final class GraphOps {
    private static final Set<String> CHANNEL_TYPES = Set.of(
        "channel", "queue-channel", "publish-subscribe-channel", "priority-channel",
        "executor-channel", "rendezvous-channel", "flux-message-channel");

    static GraphModel collapseChannels(GraphModel m) {
      Map<String, Node> byId = m.nodeMap();
      Set<String> channelIds = m.nodes.stream()
          .filter(n -> CHANNEL_TYPES.contains(n.type))
          .map(n -> n.id)
          .collect(Collectors.toSet());

      // Build adjacency lists
      Map<String, List<Link>> out = new HashMap<>();
      Map<String, List<Link>> in = new HashMap<>();
      for (Link e : m.links) {
        out.computeIfAbsent(e.from, k -> new ArrayList<>()).add(e);
        in.computeIfAbsent(e.to, k -> new ArrayList<>()).add(e);
      }

      List<Link> newLinks = new ArrayList<>();
      Set<String> removedNodes = new HashSet<>();

      for (Node n : m.nodes) {
        if (!channelIds.contains(n.id)) continue;
        // Replace A -> [channel C] -> B with A -> B (edge label keeps C)
        List<Link> incoming = in.getOrDefault(n.id, List.of());
        List<Link> outgoing = out.getOrDefault(n.id, List.of());
        if (incoming.isEmpty() || outgoing.isEmpty()) {
          // dangling channel; we'll just drop the node and its incident edges later
          removedNodes.add(n.id);
          continue;
        }
        for (Link inEdge : incoming) {
          for (Link outEdge : outgoing) {
            if (!removedNodes.contains(n.id)) {
              newLinks.add(new Link(inEdge.from, outEdge.to, n.name.isBlank() ? n.id : n.name));
            }
          }
        }
        removedNodes.add(n.id);
      }

      // Add original links that didn't touch a removed channel
      for (Link e : m.links) {
        if (!removedNodes.contains(e.from) && !removedNodes.contains(e.to)) {
          newLinks.add(e);
        }
      }

      // Keep only non-channel nodes
      List<Node> keptNodes = m.nodes.stream()
          .filter(n -> !channelIds.contains(n.id))
          .collect(Collectors.toList());

      return new GraphModel(keptNodes, newLinks);
    }

    static GraphModel filterByNodeTypes(GraphModel m, Set<String> includes) {
      Set<String> keep = m.nodes.stream().filter(n -> includes.contains(n.type)).map(n -> n.id).collect(Collectors.toSet());
      // Also keep nodes incident to kept nodes to preserve connectivity
      for (Link e : m.links) {
        if (keep.contains(e.from) || keep.contains(e.to)) {
          keep.add(e.from); keep.add(e.to);
        }
      }
      List<Node> nodes = m.nodes.stream().filter(n -> keep.contains(n.id)).collect(Collectors.toList());
      List<Link> links = m.links.stream().filter(e -> keep.contains(e.from) && keep.contains(e.to)).collect(Collectors.toList());
      return new GraphModel(nodes, links);
    }
  }

  // =====================================================================================
  //  Providers
  // =====================================================================================

  interface GraphProvider { GraphModel loadGraph(); }

  static final class SpringContextGraphProvider implements GraphProvider {
    private final List<String> xmlLocations;
    SpringContextGraphProvider(List<String> xmlLocations) { this.xmlLocations = List.copyOf(xmlLocations); }
    @Override public GraphModel loadGraph() {
      try (ClassPathXmlApplicationContext ctx = new ClassPathXmlApplicationContext(xmlLocations.toArray(String[]::new))) {
        IntegrationGraphServer server = new IntegrationGraphServer(ctx);
        IntegrationGraph g = server.getGraph();
        return mapSpringGraph(g);
      }
    }
    private GraphModel mapSpringGraph(IntegrationGraph g) {
      List<Node> nodes = g.getNodes().stream()
          .map(n -> new Node(n.getId(), n.getName(), safeType(n)))
          .collect(Collectors.toList());
      List<Link> links = g.getLinks().stream()
          .map(e -> new Link(e.getFrom(), e.getTo(), e.getChannelId()))
          .collect(Collectors.toList());
      return new GraphModel(nodes, links);
    }
    private String safeType(GraphNode n) { return n.getNodeType() == null ? "" : n.getNodeType(); }
  }

  static final class ActuatorGraphProvider implements GraphProvider {
    private final URI base;
    ActuatorGraphProvider(URI base) { this.base = base; }
    @Override public GraphModel loadGraph() {
      try {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest req = HttpRequest.newBuilder(base.resolve("/actuator/integrationgraph")).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException("HTTP " + res.statusCode() + " from actuator");
        ObjectMapper om = new ObjectMapper();
        ActuatorGraph ag = om.readValue(res.body(), ActuatorGraph.class);
        List<Node> nodes = ag.nodes.stream().map(n -> new Node(n.id, n.name, n.nodeType)).collect(Collectors.toList());
        List<Link> links = ag.links.stream().map(l -> new Link(l.from, l.to, l.channelId)).collect(Collectors.toList());
        return new GraphModel(nodes, links);
      } catch (Exception e) {
        throw new RuntimeException("Failed to load actuator graph from " + base, e);
      }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class ActuatorGraph { public List<A_Node> nodes; public List<A_Link> links; }
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class A_Node { public String id; public String name; public String nodeType; }
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class A_Link { public String from; public String to; public String channelId; }
  }

  // =====================================================================================
  //  Emitter (JavaPoet → classes for IntelliJ UML)
  // =====================================================================================

  static final class UmlEmitter {
    private final Config cfg;
    UmlEmitter(Config cfg) { this.cfg = cfg; }

    void emit(GraphModel g) {
      try {
        Files.createDirectories(cfg.outputDir);
        Map<String, Node> byId = g.nodeMap();
        Map<String, List<Link>> outgoing = g.links.stream().collect(Collectors.groupingBy(l -> l.from, LinkedHashMap::new, Collectors.toList()));

        // 1) Base Endpoint type
        JavaFile.builder(cfg.basePackage, baseEndpoint()).build().writeTo(cfg.outputDir);

        // 2) Optional marker interfaces by type
        Set<String> allTypes = g.nodes.stream().map(n -> n.type).collect(Collectors.toCollection(TreeSet::new));
        Map<String, ClassName> markerByType = new HashMap<>();
        if (cfg.generateMarkerInterfaces) {
          for (String t : allTypes) {
            if (t == null || t.isBlank()) continue;
            String ifaceName = Naming.toTypeMarkerInterface(t);
            TypeSpec iface = TypeSpec.interfaceBuilder(ifaceName)
                .addModifiers(Modifier.PUBLIC)
                .addJavadoc("Marker for SI type: $L\n", t)
                .build();
            ClassName qn = ClassName.get(cfg.basePackage, ifaceName);
            JavaFile.builder(cfg.basePackage, iface).build().writeTo(cfg.outputDir);
            markerByType.put(t, qn);
          }
        }

        // 3) Annotation for edges
        JavaFile.builder(cfg.basePackage, umlEdgeAnnotation()).build().writeTo(cfg.outputDir);

        // 4) Pre-generate classes (no relations yet) so we can reference types by ClassName
        Map<String, ClassName> classOf = new LinkedHashMap<>();
        Map<String, TypeSpec.Builder> builders = new LinkedHashMap<>();
        for (Node n : g.nodes) {
          String cls = Naming.classNameFor(n);
          ClassName cn = ClassName.get(cfg.basePackage, cls);
          classOf.put(n.id, cn);

          TypeSpec.Builder tb = TypeSpec.classBuilder(cls)
              .addModifiers(Modifier.PUBLIC)
              .superclass(ClassName.get(cfg.basePackage, "Endpoint"))
              .addJavadoc("<p>Generated from SI node.</p>\n<p><b>id</b>: $L, <b>name</b>: $L, <b>type</b>: $L</p>\n", n.id, escape(n.name), n.type)
              .addMethod(MethodSpec.constructorBuilder()
                  .addModifiers(Modifier.PUBLIC)
                  .addStatement("super($S, $S, $S)", n.id, n.name, n.type)
                  .build());

          // implement marker interface if any
          ClassName marker = markerByType.get(n.type);
          if (marker != null) tb.addSuperinterface(marker);

          builders.put(n.id, tb);
        }

        // 5) Apply relations based on mode
        if (cfg.mode == Config.Mode.INHERITANCE) {
          for (Node n : g.nodes) {
            List<Link> outs = outgoing.getOrDefault(n.id, List.of());
            if (outs.size() == 1) {
              Link l = outs.get(0);
              ClassName superCls = classOf.get(l.to);
              if (superCls != null) {
                builders.get(n.id).superclass(superCls); // overrides Endpoint → linear chain
              }
            } else {
              // Keep Endpoint as base; optionally add associations so branches are visible too
              for (int i = 0; i < outs.size(); i++) {
                Link l = outs.get(i);
                ClassName target = classOf.get(l.to);
                if (target == null) continue;
                addAssociationField(builders.get(n.id), target, i + 1, l.channelId);
              }
            }
          }
        } else { // ASSOCIATION
          for (Node n : g.nodes) {
            List<Link> outs = outgoing.getOrDefault(n.id, List.of());
            for (int i = 0; i < outs.size(); i++) {
              Link l = outs.get(i);
              ClassName target = classOf.get(l.to);
              if (target == null) continue;
              addAssociationField(builders.get(n.id), target, i + 1, l.channelId);
            }
          }
        }

        // 6) Write all classes
        for (TypeSpec.Builder tb : builders.values()) {
          JavaFile.builder(cfg.basePackage, tb.build()).build().writeTo(cfg.outputDir);
        }

        // 7) package-info placeholder (helps grouping in IntelliJ)
        TypeSpec pkgInfo = TypeSpec.classBuilder("_PackageInfo").addModifiers(Modifier.PUBLIC, Modifier.FINAL).addJavadoc("Placeholder").build();
        JavaFile.builder(cfg.basePackage, pkgInfo).build().writeTo(cfg.outputDir);

      } catch (IOException e) {
        throw new RuntimeException("Failed to emit classes", e);
      }
    }

    private void addAssociationField(TypeSpec.Builder tb, ClassName target, int idx, String channel) {
      String fieldName = "next_" + idx + "_" + target.simpleName();
      AnnotationSpec edgeAnno = AnnotationSpec.builder(ClassName.get(cfg.basePackage, "UmlEdge"))
          .addMember("channel", "$S", channel == null ? "" : channel)
          .build();
      FieldSpec f = FieldSpec.builder(target, fieldName, Modifier.PRIVATE)
          .addAnnotation(edgeAnno)
          .build();
      tb.addField(f);
      tb.addMethod(MethodSpec.methodBuilder("linkTo_" + target.simpleName())
          .addModifiers(Modifier.PUBLIC)
          .returns(ClassName.get(cfg.basePackage, tb.build().name))
          .addParameter(target, "n")
          .addStatement("this.$N = n", fieldName)
          .addStatement("return this")
          .build());
    }

    private TypeSpec baseEndpoint() {
      return TypeSpec.classBuilder("Endpoint")
          .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
          .addField(FieldSpec.builder(String.class, "id", Modifier.PROTECTED, Modifier.FINAL).build())
          .addField(FieldSpec.builder(String.class, "name", Modifier.PROTECTED, Modifier.FINAL).build())
          .addField(FieldSpec.builder(String.class, "type", Modifier.PROTECTED, Modifier.FINAL).build())
          .addMethod(MethodSpec.constructorBuilder()
              .addModifiers(Modifier.PROTECTED)
              .addParameter(String.class, "id")
              .addParameter(String.class, "name")
              .addParameter(String.class, "type")
              .addStatement("this.id = id")
              .addStatement("this.name = name")
              .addStatement("this.type = type")
              .build())
          .addMethod(MethodSpec.methodBuilder("id").addModifiers(Modifier.PUBLIC).returns(String.class).addStatement("return id").build())
          .addMethod(MethodSpec.methodBuilder("name").addModifiers(Modifier.PUBLIC).returns(String.class).addStatement("return name").build())
          .addMethod(MethodSpec.methodBuilder("type").addModifiers(Modifier.PUBLIC).returns(String.class).addStatement("return type").build())
          .build();
    }

    private TypeSpec umlEdgeAnnotation() {
      return TypeSpec.annotationBuilder("UmlEdge")
          .addModifiers(Modifier.PUBLIC)
          .addMethod(MethodSpec.methodBuilder("channel")
              .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
              .returns(String.class)
              .defaultValue("\"\"")
              .build())
          .addJavadoc("Edge metadata for UML association fields (e.g., channel name).\n")
          .build();
    }
  }

  // =====================================================================================
  //  Naming utilities
  // =====================================================================================

  static final class Naming {
    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]");

    static String classNameFor(Node n) {
      String base = (n.type + "_" + (n.name.isBlank() ? n.id : n.name));
      base = NON_ALNUM.matcher(base).replaceAll("_");
      String[] parts = base.split("_+");
      StringBuilder sb = new StringBuilder();
      for (String p : parts) {
        if (p.isBlank()) continue;
        sb.append(cap(p));
      }
      String s = sb.length() == 0 ? "N" + Math.abs(n.id.hashCode()) : sb.toString();
      if (Character.isDigit(s.charAt(0))) s = "N" + s; // ensure valid identifier
      return s;
    }

    static String toTypeMarkerInterface(String type) {
      String t = NON_ALNUM.matcher(type).replaceAll(" ").trim();
      String[] parts = t.split(" +");
      StringBuilder sb = new StringBuilder();
      for (String p : parts) sb.append(cap(p));
      String s = sb.length() == 0 ? "TypeMarker" : sb.toString();
      if (!s.endsWith("Type")) s += "Type";
      return s;
    }

    private static String cap(String s) { return s.isEmpty() ? s : s.substring(0,1).toUpperCase() + s.substring(1).toLowerCase(); }
  }

  private static String escape(String s) { return s.replace("\"", "'"); }

  // =====================================================================================
  //  Optional CLI entry point (simple arg parser)
  // =====================================================================================

  /**
   * CLI usage examples:
   *  java -cp your.jar com.example.si.umlgen.SiUmlGenerator \
   *    --xml integration/main-context.xml --xml integration/extra.xml \
   *    --out target/generated-sources/si-uml --pkg com.example.si.diagram --mode association --collapse
   *
   *  java -cp your.jar com.example.si.umlgen.SiUmlGenerator \
   *    --actuator http://localhost:8080 --out target/generated-sources/si-uml \
   *    --pkg com.example.si.diagram --mode association --collapse
   */
  public static void main(String[] args) {
    Map<String, List<String>> a = parseArgs(args);
    boolean useActuator = a.containsKey("--actuator");
    boolean useXml = a.containsKey("--xml");
    if (useActuator == useXml) {
      System.err.println("Specify exactly one of --actuator or --xml");
      System.exit(2);
    }

    Path out = Path.of(req(a, "--out"));
    String pkg = req(a, "--pkg");
    Config.Mode mode = a.getOrDefault("--mode", List.of("association")).get(0).equalsIgnoreCase("inheritance")
        ? Config.Mode.INHERITANCE : Config.Mode.ASSOCIATION;
    boolean collapse = a.containsKey("--collapse");

    if (useActuator) {
      URI base = URI.create(req(a, "--actuator"));
      run(Config.actuatorBuilder().withActuatorBaseUri(base).withOutputDir(out).withBasePackage(pkg)
          .withMode(mode).withCollapseChannels(collapse).build());
    } else {
      List<String> xmls = a.get("--xml");
      run(Config.xmlBuilder().withXmlLocations(xmls).withOutputDir(out).withBasePackage(pkg)
          .withMode(mode).withCollapseChannels(collapse).build());
    }
  }

  private static Map<String, List<String>> parseArgs(String[] args) {
    Map<String, List<String>> m = new LinkedHashMap<>();
    String k = null;
    for (String s : args) {
      if (s.startsWith("--")) { k = s; m.computeIfAbsent(k, x -> new ArrayList<>()); }
      else if (k != null) { m.get(k).add(s); }
    }
    return m;
  }
  private static String req(Map<String, List<String>> m, String key) {
    List<String> v = m.get(key);
    if (v == null || v.isEmpty()) throw new IllegalArgumentException("Missing required arg: " + key);
    return v.get(0);
  }
}

/*
Maven deps to add

<dependencies>
  <dependency>
    <groupId>org.springframework.integration</groupId>
    <artifactId>spring-integration-core</artifactId>
    <version>6.3.3</version>
  </dependency>
  <dependency>
    <groupId>org.springframework.integration</groupId>
    <artifactId>spring-integration-management</artifactId>
    <version>6.3.3</version>
  </dependency>
  <dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-context</artifactId>
    <version>6.1.10</version>
  </dependency>
  <dependency>
    <groupId>com.squareup</groupId>
    <artifactId>javapoet</artifactId>
    <version>1.13.0</version>
  </dependency>
  <dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.2</version>
  </dependency>
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>2.0.13</version>
  </dependency>
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.13</version>
    <scope>runtime</scope>
  </dependency>
</dependencies>


How to use (XML)

java -cp your.jar com.example.si.umlgen.SiUmlGenerator \
  --xml integration/main-context.xml \
  --out target/generated-sources/si-uml \
  --pkg com.yourco.si.diagram \
  --mode association --collapse

How to use (Actuator)

java -cp your.jar com.example.si.umlgen.SiUmlGenerator \
  --actuator http://localhost:8080 \
  --out target/generated-sources/si-uml \
  --pkg com.yourco.si.diagram \
  --mode association --collapse

*/
