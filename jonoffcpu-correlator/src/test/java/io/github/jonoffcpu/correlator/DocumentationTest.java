// SPDX-License-Identifier: MIT
package io.github.jonoffcpu.correlator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

/**
 * The README and the docs pages against the code: the correlator options they name exist, with the defaults they
 * state, every correlator command they show uses only its own options, and every relative link and anchor resolves.
 * Runs in the readmeTest task, whose inputs are the Markdown files it passes as {@code -Djonoffcpu.docs.root} and
 * {@code -Djonoffcpu.docs}.
 */
@Tag("readme")
class DocumentationTest {
    private static final Pattern OPTION = Pattern.compile("(?<![\\w-])--[a-z][a-z0-9-]*");
    private static final Pattern DEFAULT = Pattern.compile("Default `([^`]+)`");
    private static final Pattern COMMAND = Pattern.compile("java -jar jonoffcpu-correlator\\.jar\\b([^\\n|;&]*)");
    private static final Pattern ID = Pattern.compile("\\bid=\"([^\"]+)\"");
    /** Every command, the top-level {@code correlate} as the empty name. */
    private static final List<String> ANY = List.of("", "stacks", "top", "summarize", "merge", "export", "dump");

    private static final List<Extension> EXTENSIONS =
            List.of(TablesExtension.create(), HeadingAnchorExtension.create());

    private static Path root;
    private static List<String> documents;

    @BeforeAll
    static void documents() {
        String directory = System.getProperty("jonoffcpu.docs.root");
        assumeTrue(directory != null, "Skipping the documentation check: -Djonoffcpu.docs.root is not set");
        root = Path.of(directory);
        documents = List.of(System.getProperty("jonoffcpu.docs").split(File.pathSeparator));
        assertThat(documents).as("The checked documents").contains("README.md", "docs/analysis.md");
    }

    private static List<String> lines(String document) throws IOException {
        return Files.readAllLines(root.resolve(document), StandardCharsets.UTF_8);
    }

    /** The lines of a section starting at {@code heading}, up to the next heading of its level or above. */
    private static List<String> section(String document, String heading) throws IOException {
        List<String> lines = lines(document);
        int start = lines.indexOf(heading);
        assertThat(start).as("%s has no section %s", document, heading).isNotNegative();
        int level = heading.indexOf(' ');
        List<String> section = new ArrayList<>();
        boolean fenced = false;
        for (String line : lines.subList(start + 1, lines.size())) {
            if (line.startsWith("```")) fenced = !fenced;
            if (!fenced && line.startsWith("#") && line.indexOf(' ') <= level) break;
            section.add(line);
        }
        return section;
    }

    private static Set<String> options(String text) {
        Set<String> options = new LinkedHashSet<>();
        Matcher matcher = OPTION.matcher(text);
        while (matcher.find()) options.add(matcher.group());
        return options;
    }

    private static CommandSpec spec(String command) {
        CommandLine root = Cli.commandLine();
        return command.isEmpty()
                ? root.getCommandSpec()
                : root.getSubcommands().get(command).getCommandSpec();
    }

    /** Each table row names its options in the first column; a stated default must be the parser's. */
    @Test
    void correlatorOptionsTableMatchesParser() throws IOException {
        CommandSpec correlate = spec("");
        List<String> table = section("docs/analysis.md", "## Correlator options");
        for (String line : table) {
            if (!line.startsWith("| `--")) continue;
            String[] columns = line.split("(?<!\\\\)\\|");
            Set<String> names = options(columns[1]);
            for (String name : names) {
                assertThat(correlate.findOption(name))
                        .as("Option %s is not a correlate option", name)
                        .isNotNull();
            }
            Matcher stated = DEFAULT.matcher(columns[2]);
            if (names.size() == 1 && stated.find()) {
                OptionSpec option = correlate.findOption(names.iterator().next());
                assertThat(stated.group(1))
                        .as("The default of %s must be the parser's", option.longestName())
                        .isEqualTo(option.defaultValue());
            }
        }
        for (String name : options(String.join("\n", table))) {
            assertThat(correlate.findOption(name))
                    .as("Correlator options mentions unknown option %s", name)
                    .isNotNull();
        }
    }

    /** A section about the correlator, and the commands whose options it may name. */
    private record Section(String document, String heading, List<String> commands) {}

    /**
     * Sections about the correlator name only its options, in prose as well as in commands. Sections about the
     * converter or the kernel are left out: they name options of other tools.
     */
    @Test
    void sectionsNameOnlyCorrelatorOptions() throws IOException {
        List<Section> sections = List.of(
                new Section("README.md", "### 3. Correlate", List.of("")),
                new Section("docs/analysis.md", "## Correlate", List.of("")),
                new Section("docs/analysis.md", "## Slice and filter with the stack profile", ANY),
                new Section("docs/analysis.md", "## Transform stacks", ANY),
                new Section("docs/analysis.md", "## Merge and export profiles", ANY),
                new Section("docs/analysis.md", "## Find what to optimize", ANY),
                new Section("docs/analysis.md", "## Compare runs", ANY),
                new Section("docs/automation.md", "## Analyzing with AI agents", ANY),
                new Section("docs/automation.md", "## Analyzing with SQL", ANY));
        for (Section section : sections) {
            List<CommandSpec> commands =
                    section.commands().stream().map(DocumentationTest::spec).toList();
            for (String name : options(String.join("\n", section(section.document(), section.heading())))) {
                assertThat(commands)
                        .as("%s section %s mentions unknown option %s", section.document(), section.heading(), name)
                        .anyMatch(command -> command.findOption(name) != null);
            }
        }
    }

    /** Every {@code java -jar jonoffcpu-correlator.jar} command, in a code block or inline, uses its own options. */
    @Test
    void correlatorCommandsUseTheirOwnOptions() throws IOException {
        Set<String> subcommands = Cli.commandLine().getSubcommands().keySet();
        int commands = 0;
        for (String document : documents) {
            for (String code : code(parse(document))) {
                Matcher command = COMMAND.matcher(code.replace("\\\n", " "));
                while (command.find()) {
                    commands++;
                    String arguments = command.group(1).strip();
                    String first = arguments.isEmpty() ? "" : arguments.split("\\s+")[0];
                    String name = subcommands.contains(first) && !first.equals("help") ? first : "";
                    CommandSpec spec = spec(name);
                    for (String option : options(arguments)) {
                        assertThat(spec.findOption(option))
                                .as(
                                        "%s: %s is not an option of %s",
                                        document, option, name.isEmpty() ? "correlate" : name)
                                .isNotNull();
                    }
                }
            }
        }
        assertThat(commands).as("The documents show correlator commands").isPositive();
    }

    /** Every relative link and image resolves to a file of the repository, and every anchor to a heading. */
    @Test
    void linksResolve() {
        Map<String, Set<String>> anchors = new HashMap<>();
        List<String> broken = new ArrayList<>();
        for (String document : documents) {
            for (String destination : destinations(parse(document))) {
                if (destination.matches("^[a-z]+:.*")) continue;
                int hash = destination.indexOf('#');
                String path = hash < 0 ? destination : destination.substring(0, hash);
                String fragment = hash < 0 ? "" : destination.substring(hash + 1);
                Path target = path.isEmpty()
                        ? root.resolve(document)
                        : root.resolve(document).getParent().resolve(path).normalize();
                if (!target.startsWith(root)) {
                    broken.add(document + " links to " + destination + ", outside the repository");
                } else if (!Files.exists(target)) {
                    broken.add(document + " links to " + destination + ", which does not exist");
                } else if (!fragment.isEmpty() && target.toString().endsWith(".md")) {
                    String relative = root.relativize(target).toString().replace(File.separatorChar, '/');
                    if (!anchors.computeIfAbsent(relative, DocumentationTest::ids)
                            .contains(fragment)) {
                        broken.add(
                                document + " links to " + destination + ", but " + relative + " has no such heading");
                    }
                }
            }
        }
        assertThat(broken).as("Broken links").isEmpty();
    }

    /** A heading appears once per document, so a moved section cannot leave its old copy behind. */
    @Test
    void headingsAreUnique() {
        List<String> repeated = new ArrayList<>();
        for (String document : documents) {
            Set<String> seen = new LinkedHashSet<>();
            parse(document).accept(new AbstractVisitor() {
                @Override
                public void visit(Heading heading) {
                    StringBuilder text = new StringBuilder();
                    heading.accept(new AbstractVisitor() {
                        @Override
                        public void visit(Text literal) {
                            text.append(literal.getLiteral());
                        }

                        @Override
                        public void visit(Code code) {
                            text.append(code.getLiteral());
                        }
                    });
                    if (!seen.add(text.toString())) repeated.add(document + ": " + text);
                }
            });
        }
        assertThat(repeated).as("Headings repeated within a document").isEmpty();
    }

    private static Node parse(String document) {
        try {
            return Parser.builder()
                    .extensions(EXTENSIONS)
                    .build()
                    .parse(Files.readString(root.resolve(document), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The ids GitHub gives the document's headings, as commonmark's heading-anchor extension generates them. */
    private static Set<String> ids(String document) {
        String html = HtmlRenderer.builder().extensions(EXTENSIONS).build().render(parse(document));
        Set<String> ids = new LinkedHashSet<>();
        Matcher id = ID.matcher(html);
        while (id.find()) ids.add(id.group(1));
        return ids;
    }

    private static List<String> destinations(Node node) {
        List<String> destinations = new ArrayList<>();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                destinations.add(link.getDestination());
                visitChildren(link);
            }

            @Override
            public void visit(Image image) {
                destinations.add(image.getDestination());
                visitChildren(image);
            }
        });
        return destinations;
    }

    private static List<String> code(Node node) {
        List<String> code = new ArrayList<>();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(FencedCodeBlock block) {
                code.add(block.getLiteral());
            }

            @Override
            public void visit(Code inline) {
                code.add(inline.getLiteral());
            }
        });
        return code;
    }
}
