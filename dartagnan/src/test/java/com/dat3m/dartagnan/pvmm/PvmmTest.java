package com.dat3m.dartagnan.pvmm;

import com.dat3m.dartagnan.configuration.Arch;
import com.dat3m.dartagnan.configuration.Method;
import com.dat3m.dartagnan.configuration.Property;
import com.dat3m.dartagnan.parsers.cat.ParserCat;
import com.dat3m.dartagnan.parsers.program.ProgramParser;
import com.dat3m.dartagnan.program.Program;
import com.dat3m.dartagnan.program.event.Event;
import com.dat3m.dartagnan.program.event.MemoryEvent;
import com.dat3m.dartagnan.program.event.core.GenericVisibleEvent;
import com.dat3m.dartagnan.utils.Result;
import com.dat3m.dartagnan.utils.printer.Printer;
import com.dat3m.dartagnan.verification.VerificationTask;
import com.dat3m.dartagnan.verification.solving.ModelChecker;
import com.dat3m.dartagnan.wmm.Relation;
import com.dat3m.dartagnan.wmm.Wmm;
import com.dat3m.dartagnan.wmm.analysis.RelationAnalysis;
import com.dat3m.dartagnan.wmm.utils.graph.mutable.MapEventGraph;
import com.dat3m.dartagnan.wmm.utils.graph.mutable.MutableEventGraph;
import org.junit.Test;
import org.sosy_lab.common.configuration.Configuration;
import org.sosy_lab.java_smt.api.Model;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static com.dat3m.dartagnan.configuration.OptionNames.*;
import static com.dat3m.dartagnan.configuration.Property.CAT_SPEC;
import static com.dat3m.dartagnan.configuration.Property.PROGRAM_SPEC;
import static com.dat3m.dartagnan.utils.ResourceHelper.getRootPath;
import static com.dat3m.dartagnan.utils.Result.FAIL;
import static java.util.stream.Collectors.toMap;
import static org.junit.Assert.*;

public class PvmmTest {

    private static final Map<String, Path> libs = Map.of(
            "chains", Path.of(getRootPath("cat/chains")),
            "nochains", Path.of(getRootPath("cat/nochains"))
    );

/*
f-graph-problem-avvis-mp-b
f-graph-problem-avvis-mp-bb
f-graph-mp3-b
f-graph-mp3-bf
f-graph-mp3-sc-af
f-graph-mp3-sc-b
f-graph-mp3-sc-b
scopes-mp-acq-acq-b
*/
    private static final Map<String, Map<String, Map<String, Result>>> expected = new HashMap<>();
    static {
        try {
            expected.put("chains", readFile("expected-chains"));
            expected.put("nochains", readFile("expected-nochains"));
            Map<String, Map<String, Result>> all = readFile("expected-all");
            expected.get("chains").putAll(all);
            expected.get("nochains").putAll(all);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, Map<String, Result>> readFile(String file) throws IOException {
        file = getRootPath("litmus/VULKAN/pvmm/" + file + ".csv");
        Map<String, Map<String, Result>> result = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine();
            assertNotNull(line);
            String[] models = line.split(",");
            while ((line = br.readLine()) != null) {
                if (!line.isBlank()) {
                    String[] values = line.split(",");
                    if (values.length > 0) {
                        Map<String, Result> data = new HashMap<>();
                        for (int i = 1; i < values.length; i++) {
                            data.put(models[i], Result.valueOf(values[i]));
                        }
                        result.put(values[0], data);
                    }
                }
            }
        }
        return result;
    }

    private final Printer printer = Printer.newInstance();

    @Test
    public void checkResult() throws Exception {
        for (Map.Entry<String, Map<String, Map<String, Result>>> typeEntry : expected.entrySet()) {
            System.out.println(typeEntry.getKey());
            for (Map.Entry<String, Map<String, Result>> programEntry : typeEntry.getValue().entrySet()) {
                String program = getRootPath("litmus/VULKAN/pvmm/" + programEntry.getKey() + ".litmus");
                System.out.println(program);
                for (Map.Entry<String, Result> resultEntry : programEntry.getValue().entrySet()) {
                    Result result = resultEntry.getValue();
                    String model = getRootPath("cat/" + resultEntry.getKey() + ".cat");
                    System.out.println("    " + resultEntry.getKey());
                    VerificationTask taskEager = mkTask(program, model, PROGRAM_SPEC, typeEntry.getKey());
                    try (ModelChecker mc = ModelChecker.create(taskEager, Method.EAGER)) {
                        mc.run();
                        assertEquals(result, mc.getResult());
                    }
                    VerificationTask taskLazy = mkTask(program, model, PROGRAM_SPEC, typeEntry.getKey());
                    try (ModelChecker mc = ModelChecker.create(taskLazy, Method.LAZY)) {
                        mc.run();
                        assertEquals(result, mc.getResult());
                    }
                }
            }
        }
    }

    @Test
    public void logRelations() throws Exception {
        for (Map.Entry<String, Map<String, Map<String, Result>>> typeEntry : expected.entrySet()) {
            for (Map.Entry<String, Map<String, Result>> programEntry : typeEntry.getValue().entrySet()) {
                String program = getRootPath("litmus/VULKAN/pvmm/" + programEntry.getKey() + ".litmus");
                System.out.println(program);
                for (Map.Entry<String, Result> resultEntry : programEntry.getValue().entrySet()) {
                    Result result = resultEntry.getValue();
                    String modelPath = getRootPath("cat/" + resultEntry.getKey() + ".cat");
                    Property property = PROGRAM_SPEC;
                    if (result == FAIL) {
                        modelPath = getRootPath("cat/" + resultEntry.getKey() + "_cycle.cat");
                        property = CAT_SPEC;
                    }
                    VerificationTask task = mkTask(program, modelPath, property, typeEntry.getKey());
                    try (ModelChecker mc = ModelChecker.create(task, Method.EAGER)) {
                        mc.run();
                        assertTrue(mc.hasModel());
                        RelationAnalysis ra = mc.getEncodingContext().getAnalysisContext().get(RelationAnalysis.class);
                        Set<Relation> relations = task.getMemoryModel().getRelations();
                        Map<String, MutableEventGraph> data = extractRelationsData(task.getProgram(), relations, ra, mc.getProver().getModel());
                        data = translateEventIds(task.getProgram(), data);
                        log(resultEntry.getKey(), task.getProgram(), typeEntry.getKey(), data);
                    }
                }
            }
        }
    }

    private Map<String, MutableEventGraph> extractRelationsData(
            Program program, Set<Relation> relations, RelationAnalysis analysis, Model model) {
        Map<String, Event> events = program
                .getThreadEvents()
                .stream()
                .collect(toMap(e -> Integer.toString(e.getGlobalId()), e -> e));
        Map<String, MutableEventGraph> data = relations
                .stream()
                .collect(toMap(Relation::getNameOrTerm, r -> MapEventGraph.from(analysis.getKnowledge(r).getMustSet())));
        for (Model.ValueAssignment ast : model.asList()) {
            if (ast.getValue() instanceof Boolean bVal && bVal) {
                String[] parts = ast.getName().split(" ");
                if (parts.length == 3 && data.containsKey(parts[0])) {
                    Event e1 = events.get(parts[1]);
                    Event e2 = events.get(parts[2]);
                    assertNotNull(e1);
                    assertNotNull(e2);
                    data.get(parts[0]).add(e1, e2);
                }
            }
        }
        return data;
    }

    private Map<String, MutableEventGraph> translateEventIds(Program program, Map<String, MutableEventGraph> data) {
        int counter = 1;
        Set<Event> filter = new HashSet<>();
        List<Event> events = program
                .getThreadEvents()
                .stream()
                .sorted(Comparator.comparing(Event::getGlobalId))
                .toList();
        for (Event event : events) {
            if ((event instanceof MemoryEvent || event instanceof GenericVisibleEvent)) {
                filter.add(event);
                event.setPrintId(counter);
                counter++;
            } else {
                event.setPrintId(0);
            }
        }
        return data.entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().filter((e1, e2) -> filter.contains(e1) && filter.contains(e2))));
    }

    private void log(String model, Program program, String type, Map<String, MutableEventGraph> data) throws IOException {
        List<String> relations = data.keySet().stream().sorted().toList();
        StringBuilder sb = new StringBuilder();
        sb.append(printer.print(program));
        for (String relation : relations) {
            if (relation.matches("[a-z]+(\\#?[0-9]+[a-z_]*)?")) {
                sb.append(relation).append(": ").append(data.get(relation)).append("\n");
            }
        }
        Files.createDirectories(Path.of(getRootPath("output/data/" + type + "/" + model)));
        String filePath = getRootPath("output/data/" + type + "/" + model + "/" + program.getName() + ".log");
        Files.write(Path.of(filePath), sb.toString().getBytes());
    }

    private VerificationTask mkTask(String programPath, String modelPath, Property property, String type) throws Exception {
        VerificationTask.VerificationTaskBuilder builder = VerificationTask.builder()
                .withConfig(Configuration.builder()
                        .setOption(ENABLE_EXTENDED_RELATION_ANALYSIS, "false")
                        .setOption(ENABLE_ACTIVE_SETS, "false")
                        .build()
                )
                .withBound(1)
                .withTarget(Arch.VULKAN);
        Program program = new ProgramParser().parse(new File(programPath));
        Wmm mcm = new ParserCat(libs.get(type)).parse(new File(modelPath));
        return builder.build(program, mcm, EnumSet.of(property));
    }
}
