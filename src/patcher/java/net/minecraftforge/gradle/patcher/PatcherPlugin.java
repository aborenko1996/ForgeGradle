package net.minecraftforge.gradle.patcher;

import groovy.util.Node;
import groovy.util.XmlParser;
import groovy.xml.XmlUtil;
import net.minecraftforge.gradle.common.task.ExtractMCPData;
import net.minecraftforge.gradle.common.util.BaseRepo;
import net.minecraftforge.gradle.common.util.MavenArtifactDownloader;
import net.minecraftforge.gradle.common.util.MinecraftRepo;
import net.minecraftforge.gradle.mcp.MCPExtension;
import net.minecraftforge.gradle.mcp.MCPPlugin;
import net.minecraftforge.gradle.mcp.MCPRepo;
import net.minecraftforge.gradle.mcp.function.AccessTransformerFunction;
import net.minecraftforge.gradle.mcp.task.DownloadMCPConfigTask;
import net.minecraftforge.gradle.mcp.task.SetupMCPTask;
import net.minecraftforge.gradle.patcher.task.DownloadMCMetaTask;
import net.minecraftforge.gradle.patcher.task.DownloadMCPMappingsTask;
import net.minecraftforge.gradle.patcher.task.TaskGenerateUserdevConfig;
import net.minecraftforge.gradle.patcher.task.TaskApplyMappings;
import net.minecraftforge.gradle.patcher.task.TaskApplyPatches;
import net.minecraftforge.gradle.patcher.task.TaskApplyRangeMap;
import net.minecraftforge.gradle.patcher.task.TaskCreateExc;
import net.minecraftforge.gradle.patcher.task.TaskCreateSrg;
import net.minecraftforge.gradle.patcher.task.TaskDownloadAssets;
import net.minecraftforge.gradle.patcher.task.TaskExtractExistingFiles;
import net.minecraftforge.gradle.patcher.task.TaskExtractNatives;
import net.minecraftforge.gradle.patcher.task.TaskExtractRangeMap;
import net.minecraftforge.gradle.patcher.task.TaskFilterNewJar;
import net.minecraftforge.gradle.patcher.task.GenerateBinPatches;
import net.minecraftforge.gradle.patcher.task.TaskGeneratePatches;
import net.minecraftforge.gradle.patcher.task.TaskReobfuscateJar;
import org.apache.commons.io.IOUtils;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.plugins.ide.eclipse.GenerateEclipseClasspath;
import org.xml.sax.SAXException;

import com.google.common.collect.Lists;

import javax.annotation.Nonnull;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PatcherPlugin implements Plugin<Project> {
    private static final String MC_DEP_CONFIG = "compile";

    @Override
    public void apply(@Nonnull Project project) {
        final PatcherExtension extension = project.getExtensions().create("patcher", PatcherExtension.class, project);
        if (project.getPluginManager().findPlugin("java") == null) {
            project.getPluginManager().apply("java");
        }
        final JavaPluginConvention javaConv = (JavaPluginConvention)project.getConvention().getPlugins().get("java");
        final File natives_folder = project.file("build/natives/");

        Jar jarConfig = (Jar)project.getTasks().getByName("jar");
        JavaCompile javaCompile = (JavaCompile)project.getTasks().getByName("compileJava");

        TaskProvider<DownloadMCPMappingsTask> dlMappingsConfig = project.getTasks().register("downloadMappings", DownloadMCPMappingsTask.class);
        TaskProvider<DownloadMCMetaTask> dlMCMetaConfig = project.getTasks().register("downloadMCMeta", DownloadMCMetaTask.class);
        TaskProvider<TaskExtractNatives> extractNatives = project.getTasks().register("extractNatives", TaskExtractNatives.class);
        TaskProvider<TaskApplyPatches> applyConfig = project.getTasks().register("applyPatches", TaskApplyPatches.class);
        TaskProvider<TaskApplyMappings> toMCPConfig = project.getTasks().register("srg2mcp", TaskApplyMappings.class);
        TaskProvider<Copy> extractMapped = project.getTasks().register("extractMapped", Copy.class);
        TaskProvider<TaskCreateSrg> createMcp2Srg = project.getTasks().register("createMcp2Srg", TaskCreateSrg.class);
        TaskProvider<TaskCreateSrg> createMcp2Obf = project.getTasks().register("createMcp2Obf", TaskCreateSrg.class);
        TaskProvider<TaskCreateExc> createExc = project.getTasks().register("createExc", TaskCreateExc.class);
        TaskProvider<TaskExtractRangeMap> extractRangeConfig = project.getTasks().register("extractRangeMap", TaskExtractRangeMap.class);
        TaskProvider<TaskApplyRangeMap> applyRangeConfig = project.getTasks().register("applyRangeMap", TaskApplyRangeMap.class);
        TaskProvider<TaskApplyRangeMap> applyRangeBaseConfig = project.getTasks().register("applyRangeMapBase", TaskApplyRangeMap.class);
        TaskProvider<TaskGeneratePatches> genConfig = project.getTasks().register("genPatches", TaskGeneratePatches.class);
        TaskProvider<TaskDownloadAssets> downloadAssets = project.getTasks().register("downloadAssets", TaskDownloadAssets.class);
        TaskProvider<TaskReobfuscateJar> reobfJar = project.getTasks().register("reobfJar", TaskReobfuscateJar.class);
        TaskProvider<GenerateBinPatches> genJoinedBinPatches = project.getTasks().register("genJoinedBinPatches", GenerateBinPatches.class);
        TaskProvider<GenerateBinPatches> genClientBinPatches = project.getTasks().register("genClientBinPatches", GenerateBinPatches.class);
        TaskProvider<GenerateBinPatches> genServerBinPatches = project.getTasks().register("genServerBinPatches", GenerateBinPatches.class);
        TaskProvider<DefaultTask> genBinPatches = project.getTasks().register("genBinPatches", DefaultTask.class);
        TaskProvider<TaskFilterNewJar> filterNew = project.getTasks().register("filterJarNew", TaskFilterNewJar.class);
        TaskProvider<Jar> sourcesJar = project.getTasks().register("sourcesJar", Jar.class);
        TaskProvider<Jar> universalJar = project.getTasks().register("universalJar", Jar.class);
        TaskProvider<Jar> userdevJar = project.getTasks().register("userdevJar", Jar.class);
        TaskProvider<TaskGenerateUserdevConfig> userdevConfig = project.getTasks().register("userdevConfig", TaskGenerateUserdevConfig.class);
        TaskProvider<DefaultTask> release = project.getTasks().register("release", DefaultTask.class);

        //Add Known repos
        project.getRepositories().maven(e -> {
            e.setUrl("http://files.minecraftforge.net/maven/");
        });
        new BaseRepo.Builder()
            .add(MCPRepo.create(project))
            .add(MinecraftRepo.create(project))
            .attach(project);
        project.getRepositories().maven(e -> {
            e.setUrl("https://libraries.minecraft.net/");
            e.metadataSources(src -> src.artifact());
        });

        release.configure(task -> {
            task.dependsOn(sourcesJar, universalJar, userdevJar);
        });
        dlMappingsConfig.configure(task -> {
            task.setMappings(extension.getMappings());
        });
        extractNatives.configure(task -> {
            task.dependsOn(dlMCMetaConfig.get());
            task.setMeta(dlMCMetaConfig.get().getOutput());
            task.setOutput(natives_folder);
        });
        downloadAssets.configure(task -> {
            task.dependsOn(dlMCMetaConfig.get());
            task.setMeta(dlMCMetaConfig.get().getOutput());
        });
        applyConfig.configure(task -> {
            task.setPatches(extension.patches);
        });
        toMCPConfig.configure(task -> {
            task.dependsOn(dlMappingsConfig, applyConfig);
            task.setInput(applyConfig.get().getOutput());
            task.setMappings(dlMappingsConfig.get().getOutput());
        });
        extractMapped.configure(task -> {
            task.dependsOn(toMCPConfig);
            task.from(project.zipTree(toMCPConfig.get().getOutput()));
            task.into(extension.patchedSrc);
        });
        extractRangeConfig.configure(task -> {
            task.dependsOn(jarConfig);
            task.setOnlyIf(t -> extension.patches != null);
            task.addDependencies(jarConfig.getArchivePath());
        });

        createMcp2Srg.configure(task -> {
            task.dependsOn(dlMappingsConfig);
            task.setMappings(dlMappingsConfig.get().getOutput());
            task.toSrg();
        });
        createMcp2Obf.configure(task -> {
            task.dependsOn(dlMappingsConfig);
            task.setMappings(dlMappingsConfig.get().getOutput());
            task.toNotch();
        });
        createExc.configure(task -> {
            task.dependsOn(dlMappingsConfig);
            task.setMappings(dlMappingsConfig.get().getOutput());
        });

        applyRangeConfig.configure(task -> {
            task.dependsOn(extractRangeConfig, createMcp2Srg, createExc);
            task.setRangeMap(extractRangeConfig.get().getOutput());
            task.setSrgFiles(createMcp2Srg.get().getOutput());
            task.setExcFiles(createExc.get().getOutput());
        });
        applyRangeBaseConfig.configure(task -> {
            task.setOnlyIf(t -> extension.patches != null);
            task.dependsOn(extractRangeConfig, createMcp2Srg, createExc);
            task.setRangeMap(extractRangeConfig.get().getOutput());
            task.setSrgFiles(createMcp2Srg.get().getOutput());
            task.setExcFiles(createExc.get().getOutput());
        });
        genConfig.configure(task -> {
            task.setOnlyIf(t -> extension.patches != null);
            task.setPatches(extension.patches);
        });

        reobfJar.configure(task -> {
            task.dependsOn(jarConfig, dlMappingsConfig);
            task.setInput(jarConfig.getArchivePath());
            task.setClasspath(project.getConfigurations().getByName(MC_DEP_CONFIG));
            //TODO: Extra SRGs
        });
        genJoinedBinPatches.configure(task -> {
            task.dependsOn(reobfJar);
            task.setDirtyJar(reobfJar.get().getOutput());
            task.addPatchSet(extension.patches);
            task.setSide("joined");
        });
        genClientBinPatches.configure(task -> {
            task.dependsOn(reobfJar);
            task.setDirtyJar(reobfJar.get().getOutput());
            task.addPatchSet(extension.patches);
            task.setSide("client");
        });
        genServerBinPatches.configure(task -> {
            task.dependsOn(reobfJar);
            task.setDirtyJar(reobfJar.get().getOutput());
            task.addPatchSet(extension.patches);
            task.setSide("server");
        });
        genBinPatches.configure(task -> {
            task.dependsOn(genJoinedBinPatches.get(), genClientBinPatches.get(), genServerBinPatches.get());
        });
        filterNew.configure(task -> {
            task.dependsOn(reobfJar);
            task.setInput(reobfJar.get().getOutput());
        });
        /*
         * All sources in SRG names.
         * patches in /patches/
         */
        sourcesJar.configure(task -> {
            task.dependsOn(applyRangeConfig);
            task.from(project.zipTree(applyRangeConfig.get().getOutput()));
            task.setClassifier("sources");
        });
        /* Universal:
         * All of our classes and resources as normal jar.
         *   Should only be OUR classes, not parent patcher projects.
         */
        universalJar.configure(task -> {
            task.dependsOn(filterNew);
            task.from(project.zipTree(filterNew.get().getOutput()));
            task.from(javaConv.getSourceSets().getByName("main").getResources());
            task.setClassifier("universal");
        });
        /*UserDev:
         * config.json
         * joined.lzma
         * sources.jar
         * patches/
         *   net/minecraft/item/Item.java.patch
         * ats/
         *   at1.cfg
         *   at2.cfg
         */
        userdevJar.configure(task -> {
            task.dependsOn(userdevConfig, genJoinedBinPatches, sourcesJar, genConfig);
            task.setOnlyIf(t -> extension.srgPatches);
            task.from(userdevConfig.get().getOutput(), e -> {e.rename(f -> "config.json"); });
            task.from(genJoinedBinPatches.get().getOutput(), e -> { e.rename(f -> "joined.lzma"); });
            task.from(genConfig.get().getPatches(), e -> { e.into("patches/"); });
            task.setClassifier("userdev");
        });

        project.afterEvaluate(p -> {
            //Add PatchedSrc to a main sourceset and build range tasks
            SourceSet mainSource = javaConv.getSourceSets().getByName("main");
            applyRangeConfig.get().setSources(mainSource.getJava().getSrcDirs().stream().filter(f -> !f.equals(extension.patchedSrc)).collect(Collectors.toList()));
            applyRangeBaseConfig.get().setSources(extension.patchedSrc);
            mainSource.java(v -> { v.srcDir(extension.patchedSrc); });
            mainSource.resources(v -> { }); //TODO: Asset downloading, needs asset index from json.
            javaConv.getSourceSets().stream().forEach(s -> extractRangeConfig.get().addSources(s.getJava().getSrcDirs()));
            extractRangeConfig.get().addDependencies(javaCompile.getClasspath());

            if (extension.patches != null && !extension.patches.exists()) { //Auto-make folders so that gradle doesnt explode some tasks.
                extension.patches.mkdirs();
            }

            if (extension.patches != null) {
                sourcesJar.get().dependsOn(genConfig);
                sourcesJar.get().from(genConfig.get().getPatches(), e -> { e.into("patches/"); } );
            }

            if (extension.parent != null) { //Most of this is done after evaluate, and checks for nulls to allow the build script to override us. We can't do it in the config step because if someone configs a task in the build script it resolves our config during evaluation.
                TaskContainer tasks = extension.parent.getTasks();
                MCPPlugin mcp = extension.parent.getPlugins().findPlugin(MCPPlugin.class);
                PatcherPlugin patcher = extension.parent.getPlugins().findPlugin(PatcherPlugin.class);

                if (mcp != null) {
                    SetupMCPTask setupMCP = (SetupMCPTask)tasks.getByName("setupMCP");

                    if (extension.cleanSrc == null) {
                        extension.cleanSrc = setupMCP.getOutput();
                        applyConfig.get().dependsOn(setupMCP);
                        genConfig.get().dependsOn(setupMCP);
                    }
                    if (applyConfig.get().getClean() == null) {
                        applyConfig.get().setClean(extension.cleanSrc);
                    }
                    if (genConfig.get().getClean() == null) {
                        genConfig.get().setClean(extension.cleanSrc);
                    }

                    File mcpConfig = ((DownloadMCPConfigTask)tasks.getByName("downloadConfig")).getOutput();

                    if (createMcp2Srg.get().getSrg() == null) { //TODO: Make extractMCPData macro
                        TaskProvider<ExtractMCPData> ext = project.getTasks().register("extractSrg", ExtractMCPData.class);
                        ext.get().setConfig(mcpConfig);
                        createMcp2Srg.get().setSrg(ext.get().getOutput());
                        createMcp2Srg.get().dependsOn(ext);
                    }

                    if (createMcp2Obf.get().getSrg() == null) {
                        createMcp2Obf.get().setSrg(createMcp2Srg.get().getSrg());
                    }

                    if (createExc.get().getSrg() == null) {
                        createExc.get().setSrg(createMcp2Srg.get().getSrg());
                        createExc.get().dependsOn(createMcp2Srg);
                    }

                    if (createExc.get().getStatics() == null) {
                        TaskProvider<ExtractMCPData> ext = project.getTasks().register("extractStatic", ExtractMCPData.class);
                        ext.get().setConfig(mcpConfig);
                        ext.get().setKey("statics");
                        ext.get().setOutput(project.file("build/" + ext.get().getName() + "/output.txt"));
                        createExc.get().setStatics(ext.get().getOutput());
                        createExc.get().dependsOn(ext);
                    }

                    if (createExc.get().getConstructors() == null) {
                        TaskProvider<ExtractMCPData> ext = project.getTasks().register("extractConstructors", ExtractMCPData.class);
                        ext.get().setConfig(mcpConfig);
                        ext.get().setKey("constructors");
                        ext.get().setOutput(project.file("build/" + ext.get().getName() + "/output.txt"));
                        createExc.get().setConstructors(ext.get().getOutput());
                        createExc.get().dependsOn(ext);
                    }
                } else if (patcher != null) {
                    PatcherExtension pExt = extension.parent.getExtensions().getByType(PatcherExtension.class);
                    extension.copyFrom(pExt);

                    if (dlMappingsConfig.get().getMappings() == null) {
                        dlMappingsConfig.get().setMappings(extension.getMappings());
                    }

                    if (extension.cleanSrc == null) {
                        TaskApplyPatches task = (TaskApplyPatches)tasks.getByName(applyConfig.get().getName());
                        extension.cleanSrc = task.getOutput();
                        applyConfig.get().dependsOn(task);
                        genConfig.get().dependsOn(task);
                    }
                    if (applyConfig.get().getClean() == null) {
                        applyConfig.get().setClean(extension.cleanSrc);
                    }
                    if (genConfig.get().getClean() == null) {
                        genConfig.get().setClean(extension.cleanSrc);
                    }

                    if (createMcp2Srg.get().getSrg() == null) {
                        ExtractMCPData extract = ((ExtractMCPData)tasks.getByName("extractSrg"));
                        if (extract != null) {
                            createMcp2Srg.get().setSrg(extract.getOutput());
                            createMcp2Srg.get().dependsOn(extract);
                        } else {
                            TaskCreateSrg task = (TaskCreateSrg)tasks.getByName(createMcp2Srg.get().getName());
                            createMcp2Srg.get().setSrg(task.getSrg());
                            createMcp2Srg.get().dependsOn(task);
                        }
                    }

                    if (createMcp2Obf.get().getSrg() == null) {
                        createMcp2Obf.get().setSrg(createMcp2Srg.get().getSrg());
                        createMcp2Obf.get().dependsOn(createMcp2Srg.get());
                    }

                    if (createExc.get().getSrg() == null) { //TODO: Make a macro for Srg/Static/Constructors
                        ExtractMCPData extract = ((ExtractMCPData)tasks.getByName("extractSrg"));
                        if (extract != null) {
                            createExc.get().setSrg(extract.getOutput());
                            createExc.get().dependsOn(extract);
                        } else {
                            TaskCreateSrg task = (TaskCreateSrg)tasks.getByName(createExc.get().getName());
                            createExc.get().setSrg(task.getSrg());
                            createExc.get().dependsOn(task);
                        }
                    }
                    if (createExc.get().getStatics() == null) {
                        ExtractMCPData extract = ((ExtractMCPData)tasks.getByName("extractStatic"));
                        if (extract != null) {
                            createExc.get().setStatics(extract.getOutput());
                            createExc.get().dependsOn(extract);
                        } else {
                            TaskCreateExc task = (TaskCreateExc)tasks.getByName(createExc.get().getName());
                            createExc.get().setStatics(task.getStatics());
                            createExc.get().dependsOn(task);
                        }
                    }
                    if (createExc.get().getConstructors() == null) {
                        ExtractMCPData extract = ((ExtractMCPData)tasks.getByName("extractConstructors"));
                        if (extract != null) {
                            createExc.get().setConstructors(extract.getOutput());
                            createExc.get().dependsOn(extract);
                        } else {
                            TaskCreateExc task = (TaskCreateExc)tasks.getByName(createExc.get().getName());
                            createExc.get().setConstructors(task.getConstructors());
                            createExc.get().dependsOn(task);
                        }
                    }
                    for (TaskProvider<GenerateBinPatches> task : Lists.newArrayList(genJoinedBinPatches, genClientBinPatches, genServerBinPatches)) {
                        GenerateBinPatches pgen = (GenerateBinPatches)tasks.getByName(task.get().getName());
                        for (File patches : pgen.getPatchSets()) {
                            task.get().addPatchSet(patches);
                        }
                    }

                    filterNew.get().dependsOn(tasks.getByName("jar"));
                    filterNew.get().addBlacklist(((Jar)tasks.getByName("jar")).getArchivePath());
                } else {
                    throw new IllegalStateException("Parent must either be a Patcher or MCP project");
                }
            }
            project.getDependencies().add(MC_DEP_CONFIG, "net.minecraft:client:" + extension.mcVersion + ":extra");
            project.getDependencies().add(MC_DEP_CONFIG, "net.minecraft:client:" + extension.mcVersion + ":data");

            if (dlMCMetaConfig.get().getMCVersion() == null) {
                dlMCMetaConfig.get().setMCVersion(extension.mcVersion);
            }

            if (!extension.getAccessTransformers().isEmpty()) {
                Project mcp = getMcpParent(project);
                if (mcp == null) {
                    throw new IllegalStateException("AccessTransformers specified, with no MCP Parent");
                }
                SetupMCPTask setupMCP = (SetupMCPTask)mcp.getTasks().getByName("setupMCP");
                setupMCP.addPreDecompile(project.getName() + "AccessTransformer", new AccessTransformerFunction(mcp, extension.getAccessTransformers()));
                extension.getAccessTransformers().forEach(f -> {
                    userdevJar.get().from(f, e -> e.into("ats/"));
                    userdevConfig.get().addAT(f);
                });
            }

            applyRangeConfig.get().setExcFiles(extension.getExcs());
            applyRangeBaseConfig.get().setExcFiles(extension.getExcs());

            if (!extension.getExtraMappings().isEmpty()) {
                extension.getExtraMappings().stream().filter(e -> e instanceof File).map(e -> (File)e).forEach(e -> {
                    userdevJar.get().from(e, c -> c.into("srgs/"));
                    userdevConfig.get().addSRG(e);
                });
                extension.getExtraMappings().stream().filter(e -> e instanceof String).map(e -> (String)e).forEach(e -> userdevConfig.get().addSRGLine(e));
            }

            if (userdevConfig.get().getTool() == null) {
                userdevConfig.get().setTool("net.minecraftforge:binarypatcher:" + genJoinedBinPatches.get().getResolvedVersion() + ":fatjar");
                userdevConfig.get().setArguments("--clean", "{clean}", "--output", "{output}", "--apply", "{patch}");
            }
            if (userdevConfig.get().getUniversal() == null) {
                userdevConfig.get().setUniversal(project.getGroup().toString() + ':' + universalJar.get().getBaseName() + ':' + project.getVersion() + ':' + universalJar.get().getClassifier() + '@' + universalJar.get().getExtension());
            }
            if (userdevConfig.get().getSource() == null) {
                userdevConfig.get().setSource(project.getGroup().toString() + ':' + sourcesJar.get().getBaseName() + ':' + project.getVersion() + ':' + sourcesJar.get().getClassifier() + '@' + sourcesJar.get().getExtension());
            }

            //Allow generation of patches to skip S2S. For in-dev patches while the code doesn't compile.
            if (extension.srgPatches) {
                genConfig.get().dependsOn(applyRangeBaseConfig);
                genConfig.get().setModified(applyRangeBaseConfig.get().getOutput());
            } else {
                //Remap the 'clean' with out mappings.
                TaskApplyMappings toMCPClean = project.getTasks().register("srg2mcpClean", TaskApplyMappings.class).get();
                toMCPClean.dependsOn(dlMappingsConfig, Lists.newArrayList(applyConfig.get().getDependsOn()));
                toMCPClean.setInput(applyConfig.get().getClean());
                toMCPClean.setMappings(dlMappingsConfig.get().getOutput());

                //Zip up the current working folder as genPatches takes a zip
                Zip dirtyZip = project.getTasks().register("patchedZip", Zip.class).get();
                dirtyZip.from(extension.patchedSrc);
                dirtyZip.setArchiveName("output.zip");
                dirtyZip.setDestinationDir(project.file("build/" + dirtyZip.getName() + "/"));

                //Fixup the inputs.
                applyConfig.get().setDependsOn(Lists.newArrayList(toMCPClean));
                applyConfig.get().setClean(toMCPClean.getOutput());
                genConfig.get().setDependsOn(Lists.newArrayList(toMCPClean, dirtyZip));
                genConfig.get().setClean(toMCPClean.getOutput());
                genConfig.get().setModified(dirtyZip.getArchivePath());
            }

            // Configure reobf and packages:
            {
                Project mcp = getMcpParent(project);
                if (mcp == null) {
                    throw new IllegalStateException("Could not find MCP parent project, you must specify a parent chain to MCP.");
                }
                String mcp_version = mcp.getExtensions().findByType(MCPExtension.class).getConfig().getVersion();


                File client = MavenArtifactDownloader.single(project, "net.minecraft:client:" + mcp_version + ":srg", true);
                File server = MavenArtifactDownloader.single(project, "net.minecraft:server:" + mcp_version + ":srg", true);
                File joined = MavenArtifactDownloader.single(project, "net.minecraft:joined:" + mcp_version + ":srg", true);

                reobfJar.get().dependsOn(createMcp2Srg);
                reobfJar.get().setSrg(createMcp2Srg.get().getOutput());
                //TODO: Extra SRGs, I dont think this is needed tho...

                genJoinedBinPatches.get().dependsOn(createMcp2Srg);
                genJoinedBinPatches.get().setSrg(createMcp2Srg.get().getOutput());
                genJoinedBinPatches.get().setCleanJar(joined);

                genClientBinPatches.get().dependsOn(createMcp2Srg);
                genClientBinPatches.get().setSrg(createMcp2Srg.get().getOutput());
                genClientBinPatches.get().setCleanJar(client);

                genServerBinPatches.get().dependsOn(createMcp2Srg);
                genServerBinPatches.get().setSrg(createMcp2Srg.get().getOutput());
                genServerBinPatches.get().setCleanJar(server);

                filterNew.get().dependsOn(createMcp2Srg);
                filterNew.get().setSrg(createMcp2Srg.get().getOutput());
                filterNew.get().addBlacklist(joined);
            }

            //Make sure tasks that require a valid classpath happen after making the classpath
            p.getTasks().withType(GenerateEclipseClasspath.class, t -> { t.dependsOn(extractNatives.get(), downloadAssets.get()); });
            //TODO: IntelliJ plugin?

            doEclipseFixes(project, natives_folder, extension, downloadAssets.get().getOutput());

            if (project.hasProperty("UPDATE_MAPPINGS")) {
                String version = (String)project.property("UPDATE_MAPPINGS");
                String channel = project.hasProperty("UPDATE_MAPPINGS_CHANNEL") ? (String)project.property("UPDATE_MAPPINGS_CHANNEL") : "snapshot";

                TaskProvider<DownloadMCPMappingsTask> dlMappingsNew = project.getTasks().register("downloadMappingsNew", DownloadMCPMappingsTask.class);
                dlMappingsNew.get().setMappings("de.oceanlabs.mcp:mcp_" + channel + ":" + version + "@zip");

                TaskProvider<TaskApplyMappings> toMCPNew = project.getTasks().register("srg2mcpNew", TaskApplyMappings.class);
                toMCPNew.get().dependsOn(dlMappingsNew.get(), applyRangeConfig.get());
                toMCPNew.get().setInput(applyRangeConfig.get().getOutput());
                toMCPNew.get().setMappings(dlMappingsConfig.get().getOutput());

                TaskProvider<TaskExtractExistingFiles> extractMappedNew = project.getTasks().register("extractMappedNew", TaskExtractExistingFiles.class);
                extractMappedNew.get().dependsOn(toMCPNew.get());
                extractMappedNew.get().setArchive(toMCPNew.get().getOutput());
                for (File dir : mainSource.getJava().getSrcDirs()) {
                    if (dir.equals(extension.patchedSrc)) //Don't overwrite the patched code, re-setup the project.
                        continue;
                    extractMappedNew.get().addTarget(dir);
                }

                TaskProvider<DefaultTask> updateMappings = project.getTasks().register("updateMappings", DefaultTask.class);
                updateMappings.get().dependsOn(extractMappedNew.get());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void doEclipseFixes(Project project, File natives, PatcherExtension extension, File assets) {
        final String LIB_ATTR = "org.eclipse.jdt.launching.CLASSPATH_ATTR_LIBRARY_PATH_ENTRY";
        project.getTasks().withType(GenerateEclipseClasspath.class, task -> {
            task.doFirst(t -> {
                task.getClasspath().getSourceSets().forEach(s -> {
                    if (s.getName().equals("main")) { //Eclipse requires main to exist.. or it gets wonkey
                        s.getAllSource().getSrcDirs().stream().filter(f -> !f.exists()).forEach(File::mkdirs);
                    }
                });
            });
            task.doLast(t -> {
                try {
                    Node xml = new XmlParser().parse(task.getOutputFile());

                    List<Node> entries = (ArrayList<Node>)xml.get("classpathentry");
                    Set<String> paths = new HashSet<>();
                    List<Node> remove = new ArrayList<>();
                    entries.stream().filter(e -> "src".equals(e.get("@kind"))).forEach(e -> {
                        if (!paths.add((String)e.get("@path"))) { //Eclipse likes to duplicate things... No idea why, lets kill them off
                            remove.add(e);
                        }
                        if (((List<Node>)e.get("attributes")).isEmpty()) {
                            e.appendNode("attributes");
                        }
                        Node attr = ((List<Node>)e.get("attributes")).get(0);
                        if (((List<Node>)attr.get("attribute")).stream().noneMatch(n -> LIB_ATTR.equals(n.get("@name")))) {
                            attr.appendNode("attribute", props("name", LIB_ATTR, "value", natives.getAbsolutePath()));
                        }
                    });
                    remove.forEach(xml::remove);
                    try (OutputStream fos = new FileOutputStream(task.getOutputFile())) {
                        IOUtils.write(XmlUtil.serialize(xml), fos, StandardCharsets.UTF_8);
                    }

                    File run_dir = project.file("run");
                    if (!run_dir.exists()) {
                        run_dir.mkdirs();
                    }

                    String niceName = project.getName().substring(0, 1).toUpperCase() + project.getName().substring(1);
                    for (boolean client : new boolean[] {true, false}) {
                        xml = new Node(null, "launchConfiguration", props("type", "org.eclipse.jdt.launching.localJavaApplication"));
                        String main = client ? (extension.getClientRun().getMain() != null ? extension.getClientRun().getMain() : "mcp.client.Start") :
                                               (extension.getServerRun().getMain() != null ? extension.getServerRun().getMain() : "net.minecraft.server.MinecraftServer" );
                        xml.appendNode("stringAttribute", props("key", "org.eclipse.jdt.launching.MAIN_TYPE", "value", main));
                        xml.appendNode("stringAttribute", props("key", "org.eclipse.jdt.launching.PROJECT_ATTR", "value", project.getName()));
                        xml.appendNode("stringAttribute", props("key", "org.eclipse.jdt.launching.WORKING_DIRECTORY", "value", run_dir.getAbsolutePath()));

                        Node env = xml.appendNode("mapAttribute", props("key", "org.eclipse.debug.core.environmentVariables"));
                        env.appendNode("mapEntry", props("key", "assetDirectory", "value", assets.getAbsolutePath()));
                        (client ? extension.getClientRun() : extension.getServerRun()).getEnvironment().forEach((k,v) -> env.appendNode("mapEntry", props("key", k, "value", v)));

                        String props = (client ? extension.getClientRun() : extension.getServerRun()).getProperties().entrySet().stream().map(e -> {
                            String val = e.getValue();
                            if (val.indexOf(' ') != -1) val = "\"" + e.getValue().replaceAll("\"", "\\\"") + "\"";
                            return "-D" + e.getKey() + "=" + val;
                        }).collect(Collectors.joining("\n"));

                        if (!props.isEmpty()) {
                            xml.appendNode("stringAttribute", props("key", "org.eclipse.jdt.launching.VM_ARGUMENTS", "value", props));
                        }

                        try (OutputStream fos = new FileOutputStream(project.file(client ? "RunClient" + niceName +".launch" : "RunServer" + niceName +".launch"))) {
                            IOUtils.write(XmlUtil.serialize(xml), fos, StandardCharsets.UTF_8);
                        }
                    }
                } catch (IOException | SAXException | ParserConfigurationException e) {
                    throw new RuntimeException(e);
                }
            });
        });
    }

    private Map<String, String> props(String... data) {
        if (data.length % 2 != 0) {
            throw new IllegalArgumentException("Properties must be key,value pairs");
        }
        Map<String, String> ret = new HashMap<>();
        for (int x = 0; x < data.length; x += 2) {
            ret.put(data[x], data[x + 1]);
        }
        return ret;
    }

    private Project getMcpParent(Project project) {
        final PatcherExtension extension = project.getExtensions().findByType(PatcherExtension.class);
        if (extension == null || extension.parent == null) {
            return null;
        }
        MCPPlugin mcp = extension.parent.getPlugins().findPlugin(MCPPlugin.class);
        PatcherPlugin patcher = extension.parent.getPlugins().findPlugin(PatcherPlugin.class);
        if (mcp != null) {
            return extension.parent;
        } else if (patcher != null) {
            return getMcpParent(extension.parent);
        }
        return null;
    }
}