package org.jetbrains.jps.cmdline;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.io.DataOutputStream;
import groovy.util.Node;
import groovy.util.XmlParser;
import org.codehaus.groovy.runtime.MethodClosure;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.Channels;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.ether.dependencyView.Callbacks;
import org.jetbrains.jps.Library;
import org.jetbrains.jps.Module;
import org.jetbrains.jps.Project;
import org.jetbrains.jps.Sdk;
import org.jetbrains.jps.api.*;
import org.jetbrains.jps.artifacts.Artifact;
import org.jetbrains.jps.idea.IdeaProjectLoader;
import org.jetbrains.jps.idea.SystemOutErrorReporter;
import org.jetbrains.jps.incremental.*;
import org.jetbrains.jps.incremental.fs.BuildFSState;
import org.jetbrains.jps.incremental.fs.RootDescriptor;
import org.jetbrains.jps.incremental.messages.*;
import org.jetbrains.jps.incremental.storage.BuildDataManager;
import org.jetbrains.jps.incremental.storage.ProjectTimestamps;
import org.jetbrains.jps.incremental.storage.Timestamps;
import org.jetbrains.jps.server.ProjectDescriptor;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
* @author Eugene Zhuravlev
*         Date: 4/17/12
*/
final class BuildSession implements Runnable, CanceledStatus {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.jps.cmdline.BuildSession");
  public static final String IDEA_PROJECT_DIRNAME = ".idea";
  private static final String FS_STATE_FILE = "fs_state.dat";
  private final UUID mySessionId;
  private final Channel myChannel;
  private volatile boolean myCanceled = false;
  // globals
  private final Map<String, String> myPathVars;
  private final List<GlobalLibrary> myGlobalLibraries;
  private final String myGlobalEncoding;
  private final String myIgnorePatterns;
  // build params
  private final BuildType myBuildType;
  private final Set<String> myModules;
  private final List<String> myArtifacts;
  private final List<String> myFilePaths;
  private final Map<String, String> myBuilderParams;
  private String myProjectPath;
  @Nullable
  private CmdlineRemoteProto.Message.ControllerMessage.FSEvent myInitialFSDelta;
  // state
  private EventsProcessor myEventsProcessor = new EventsProcessor();
  private volatile long myLastEventOrdinal;
  private volatile ProjectDescriptor myProjectDescriptor;
  private final Map<Pair<String, String>, ConstantSearchFuture> mySearchTasks = Collections.synchronizedMap(new HashMap<Pair<String, String>, ConstantSearchFuture>());
  private final ConstantSearch myConstantSearch = new ConstantSearch();

  BuildSession(UUID sessionId,
               Channel channel,
               CmdlineRemoteProto.Message.ControllerMessage.ParametersMessage params,
               @Nullable CmdlineRemoteProto.Message.ControllerMessage.FSEvent delta) {
    mySessionId = sessionId;
    myChannel = channel;

    // globals
    myPathVars = new HashMap<String, String>();
    final CmdlineRemoteProto.Message.ControllerMessage.GlobalSettings globals = params.getGlobalSettings();
    for (CmdlineRemoteProto.Message.KeyValuePair variable : globals.getPathVariableList()) {
      myPathVars.put(variable.getKey(), variable.getValue());
    }
    myGlobalLibraries = new ArrayList<GlobalLibrary>();
    for (CmdlineRemoteProto.Message.ControllerMessage.GlobalSettings.GlobalLibrary library : globals.getGlobalLibraryList()) {
      myGlobalLibraries.add(
        library.hasHomePath() ?
        new SdkLibrary(library.getName(), library.getTypeName(), library.hasVersion() ? library.getVersion() : null, library.getHomePath(), library.getPathList(), library.hasAdditionalDataXml() ? library.getAdditionalDataXml() : null) :
        new GlobalLibrary(library.getName(), library.getPathList())
      );
    }
    myGlobalEncoding = globals.hasGlobalEncoding()? globals.getGlobalEncoding() : null;
    myIgnorePatterns = globals.hasIgnoredFilesPatterns()? globals.getIgnoredFilesPatterns() : null;

    // session params
    myProjectPath = FileUtil.toCanonicalPath(params.getProjectId());
    myBuildType = convertCompileType(params.getBuildType());
    myModules = new HashSet<String>(params.getModuleNameList());
    myArtifacts = params.getArtifactNameList();
    myFilePaths = params.getFilePathList();
    myBuilderParams = new HashMap<String, String>();
    for (CmdlineRemoteProto.Message.KeyValuePair pair : params.getBuilderParameterList()) {
      myBuilderParams.put(pair.getKey(), pair.getValue());
    }
    myInitialFSDelta = delta;
  }

  public void run() {
    Throwable error = null;
    final Ref<Boolean> hasErrors = new Ref<Boolean>(false);
    final Ref<Boolean> markedFilesUptodate = new Ref<Boolean>(false);
    try {
      runBuild(myProjectPath, myBuildType, myModules, myArtifacts, myBuilderParams, myFilePaths, new MessageHandler() {
        public void processMessage(BuildMessage buildMessage) {
          final CmdlineRemoteProto.Message.BuilderMessage response;
          if (buildMessage instanceof FileGeneratedEvent) {
            final Collection<Pair<String, String>> paths = ((FileGeneratedEvent)buildMessage).getPaths();
            response = !paths.isEmpty() ? CmdlineProtoUtil.createFileGeneratedEvent(paths) : null;
          }
          else if (buildMessage instanceof UptoDateFilesSavedEvent) {
            markedFilesUptodate.set(true);
            response = null;
          }
          else if (buildMessage instanceof CompilerMessage) {
            markedFilesUptodate.set(true);
            final CompilerMessage compilerMessage = (CompilerMessage)buildMessage;
            final String text = compilerMessage.getCompilerName() + ": " + compilerMessage.getMessageText();
            final BuildMessage.Kind kind = compilerMessage.getKind();
            if (kind == BuildMessage.Kind.ERROR) {
              hasErrors.set(true);
            }
            response = CmdlineProtoUtil.createCompileMessage(
              kind, text, compilerMessage.getSourcePath(),
              compilerMessage.getProblemBeginOffset(), compilerMessage.getProblemEndOffset(),
              compilerMessage.getProblemLocationOffset(), compilerMessage.getLine(), compilerMessage.getColumn(),
              -1.0f);
          }
          else {
            float done = -1.0f;
            if (buildMessage instanceof ProgressMessage) {
              done = ((ProgressMessage)buildMessage).getDone();
            }
            response = CmdlineProtoUtil.createCompileProgressMessageResponse(buildMessage.getMessageText(), done);
          }
          if (response != null) {
            Channels.write(myChannel, CmdlineProtoUtil.toMessage(mySessionId, response));
          }
        }
      }, this);
    }
    catch (Throwable e) {
      LOG.info(e);
      error = e;
    }
    finally {
      finishBuild(error, hasErrors.get(), markedFilesUptodate.get());
    }
  }

  private void runBuild(String projectPath, BuildType buildType, Set<String> modules, Collection<String> artifacts, Map<String, String> builderParams, Collection<String> paths, final MessageHandler msgHandler, CanceledStatus cs) throws Throwable{
    boolean forceCleanCaches = false;
    ProjectDescriptor pd;
    final Project project = loadProject(projectPath);
    final File dataStorageRoot = Utils.getDataStorageRoot(project);

    final boolean inMemoryMappingsDelta = System.getProperty(GlobalOptions.USE_MEMORY_TEMP_CACHE_OPTION) != null;
    ProjectTimestamps projectTimestamps = null;
    BuildDataManager dataManager = null;
    try {
      projectTimestamps = new ProjectTimestamps(dataStorageRoot);
      dataManager = new BuildDataManager(dataStorageRoot, inMemoryMappingsDelta);
      if (dataManager.versionDiffers()) {
        forceCleanCaches = true;
        msgHandler.processMessage(new CompilerMessage("build", BuildMessage.Kind.INFO, "Dependency data format has changed, project rebuild required"));
      }
    }
    catch (Exception e) {
      // second try
      LOG.info(e);
      if (projectTimestamps != null) {
        projectTimestamps.close();
      }
      if (dataManager != null) {
        dataManager.close();
      }
      forceCleanCaches = true;
      FileUtil.delete(dataStorageRoot);
      projectTimestamps = new ProjectTimestamps(dataStorageRoot);
      dataManager = new BuildDataManager(dataStorageRoot, inMemoryMappingsDelta);
      // second attempt succeded
      msgHandler.processMessage(new CompilerMessage("build", BuildMessage.Kind.INFO, "Project rebuild forced: " + e.getMessage()));
    }

    final BuildFSState fsState = new BuildFSState(false);
    pd = new ProjectDescriptor(project, fsState, projectTimestamps, dataManager, BuildLoggingManager.DEFAULT);
    myProjectDescriptor = pd;

    try {
      loadFsState(myProjectDescriptor, dataStorageRoot, myInitialFSDelta);
      // free memory
      myInitialFSDelta = null;
      // ensure events from controller are processed after FSState initialization
      myEventsProcessor.startProcessing();

      for (int attempt = 0; attempt < 2; attempt++) {
        if (forceCleanCaches && modules.isEmpty() && paths.isEmpty()) {
          // if compilation scope is the whole project and cache rebuild is forced, use PROJECT_REBUILD for faster compilation
          buildType = BuildType.PROJECT_REBUILD;
        }

        final Timestamps timestamps = pd.timestamps.getStorage();

        final CompileScope compileScope = createCompilationScope(buildType, pd, timestamps, modules, artifacts, paths);
        final IncProjectBuilder builder = new IncProjectBuilder(pd, BuilderRegistry.getInstance(), timestamps, builderParams, cs, myConstantSearch);
        builder.addMessageHandler(msgHandler);
        try {
          switch (buildType) {
            case PROJECT_REBUILD:
              builder.build(compileScope, false, true, forceCleanCaches);
              break;

            case FORCED_COMPILATION:
              builder.build(compileScope, false, false, forceCleanCaches);
              break;

            case MAKE:
              builder.build(compileScope, true, false, forceCleanCaches);
              break;

            case CLEAN:
              //todo[nik]
      //        new ProjectBuilder(new GantBinding(), project).clean();
              break;
          }
          break; // break attempts loop
        }
        catch (RebuildRequestedException e) {
          if (attempt == 0) {
            LOG.info(e);
            forceCleanCaches = true;
          }
          else {
            throw e;
          }
        }
      }
    }
    finally {
      saveData(pd, dataStorageRoot);
    }
  }

  private void saveData(ProjectDescriptor pd, File dataStorageRoot) {
    final boolean wasInterrupted = Thread.interrupted();
    try {
      saveFsState(dataStorageRoot, pd.fsState, myLastEventOrdinal);
      pd.release();
    }
    finally {
      if (wasInterrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public void processFSEvent(final CmdlineRemoteProto.Message.ControllerMessage.FSEvent event) {
    myEventsProcessor.submit(new Runnable() {
      @Override
      public void run() {
        try {
          applyFSEvent(myProjectDescriptor, event);
        }
        catch (IOException e) {
          LOG.error(e);
        }
      }
    });
  }

  public void processConstantSearchResult(CmdlineRemoteProto.Message.ControllerMessage.ConstantSearchResult result) {
    final ConstantSearchFuture future = mySearchTasks.remove(Pair.create(result.getOwnerClassName(), result.getFieldName()));
    if (future != null) {
      if (result.getIsSuccess()) {
        final List<String> paths = result.getPathList();
        final List<File> files = new ArrayList<File>(paths.size());
        for (String path : paths) {
          files.add(new File(path));
        }
        future.setResult(files);
      }
      else {
        future.setDone();
      }
    }
  }

  private void applyFSEvent(ProjectDescriptor pd, @Nullable CmdlineRemoteProto.Message.ControllerMessage.FSEvent event) throws IOException {
    if (event == null) {
      return;
    }

    final Timestamps timestamps = pd.timestamps.getStorage();

    for (String deleted : event.getDeletedPathsList()) {
      final File file = new File(deleted);
      final RootDescriptor rd = pd.rootsIndex.getModuleAndRoot(file);
      if (rd != null) {
        pd.fsState.registerDeleted(rd.module, file, rd.isTestRoot, timestamps);
      }
    }
    for (String changed : event.getChangedPathsList()) {
      final File file = new File(changed);
      final RootDescriptor rd = pd.rootsIndex.getModuleAndRoot(file);
      if (rd != null) {
        pd.fsState.markDirty(file, rd, timestamps);
      }
    }

    myLastEventOrdinal += 1;
  }

  private static void saveFsState(File dataStorageRoot, BuildFSState state, long lastEventOrdinal) {
    final File file = new File(dataStorageRoot, FS_STATE_FILE);

    BufferExposingByteArrayOutputStream bytes = new BufferExposingByteArrayOutputStream();
    try {
      final DataOutputStream out = new DataOutputStream(bytes);
      out.writeLong(lastEventOrdinal);
      try {
        state.save(out);
      }
      finally {
        out.close();
      }
    }
    catch (IOException e) {
      LOG.error(e);
      return;
    }

    FileOutputStream fos = null;
    try {
      fos = new FileOutputStream(file);
    }
    catch (FileNotFoundException e) {
      FileUtil.createIfDoesntExist(file);
    }

    try {
      if (fos == null) {
        fos = new FileOutputStream(file);
      }
      try {
        fos.write(bytes.getInternalBuffer(), 0, bytes.size());
      }
      finally {
        fos.close();
      }
    }
    catch (IOException e) {
      LOG.error(e);
      FileUtil.delete(file);
    }
  }

  private void loadFsState(final ProjectDescriptor pd, File dataStorageRoot, CmdlineRemoteProto.Message.ControllerMessage.FSEvent initialEvent) {
    final File file = new File(dataStorageRoot, FS_STATE_FILE);
    try {
      final InputStream fs = new FileInputStream(file);
      byte[] bytes;
      try {
        bytes = FileUtil.loadBytes(fs, (int)file.length());
      }
      finally {
        fs.close();
      }

      final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
      try {
        final long savedOrdinal = in.readLong();
        if (initialEvent != null && (savedOrdinal + 1L == initialEvent.getOrdinal())) {
          pd.fsState.load(in);
          myLastEventOrdinal = savedOrdinal;
          applyFSEvent(pd, initialEvent);
        }
        else {
          // either the first start or some events were lost, forcing scan
          pd.fsState.clearAll();
          myLastEventOrdinal = initialEvent != null? initialEvent.getOrdinal() : 0L;
        }
      }
      finally {
        in.close();
      }
      return; // successfully initialized

    }
    catch (FileNotFoundException ignored) {
    }
    catch (Throwable e) {
      LOG.error(e);
    }
    myLastEventOrdinal = initialEvent != null? initialEvent.getOrdinal() : 0L;
    pd.fsState.clearAll();
  }

  private void finishBuild(Throwable error, boolean hadBuildErrors, boolean markedUptodateFiles) {
    CmdlineRemoteProto.Message lastMessage = null;
    try {
      if (error != null) {
        Throwable cause = error.getCause();
        if (cause == null) {
          cause = error;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        cause.printStackTrace(new PrintStream(out));

        final StringBuilder messageText = new StringBuilder();
        messageText.append("Internal error: (").append(cause.getClass().getName()).append(") ").append(cause.getMessage());
        final String trace = out.toString();
        if (!trace.isEmpty()) {
          messageText.append("\n").append(trace);
        }
        lastMessage = CmdlineProtoUtil.toMessage(mySessionId, CmdlineProtoUtil.createFailure(messageText.toString(), cause));
      }
      else {
        CmdlineRemoteProto.Message.BuilderMessage.BuildEvent.Status status = CmdlineRemoteProto.Message.BuilderMessage.BuildEvent.Status.SUCCESS;
        if (myCanceled) {
          status = CmdlineRemoteProto.Message.BuilderMessage.BuildEvent.Status.CANCELED;
        }
        else if (hadBuildErrors) {
          status = CmdlineRemoteProto.Message.BuilderMessage.BuildEvent.Status.ERRORS;
        }
        else if (!markedUptodateFiles){
          status = CmdlineRemoteProto.Message.BuilderMessage.BuildEvent.Status.UP_TO_DATE;
        }
        lastMessage = CmdlineProtoUtil.toMessage(mySessionId, CmdlineProtoUtil.createBuildCompletedEvent("build completed", status));
      }
    }
    catch (Throwable e) {
      lastMessage = CmdlineProtoUtil.toMessage(mySessionId, CmdlineProtoUtil.createFailure(e.getMessage(), e));
    }
    finally {
      try {
        Channels.write(myChannel, lastMessage).await();
      }
      catch (InterruptedException e) {
        LOG.info(e);
      }
    }
  }

  public void cancel() {
    myCanceled = true;
  }

  @Override
  public boolean isCanceled() {
    return myCanceled;
  }


  private Project loadProject(String projectPath) {
    final long start = System.currentTimeMillis();
    try {
      final Project project = new Project();

      initSdksAndGlobalLibraries(project);

      final File projectFile = new File(projectPath);

      //String root = dirBased ? projectPath : projectFile.getParent();

      final String loadPath = isDirectoryBased(projectFile) ? new File(projectFile, IDEA_PROJECT_DIRNAME).getPath() : projectPath;
      IdeaProjectLoader.loadFromPath(project, loadPath, myPathVars, null, new SystemOutErrorReporter(false));
      final String globalEncoding = myGlobalEncoding;
      if (globalEncoding != null && project.getProjectCharset() == null) {
        project.setProjectCharset(globalEncoding);
      }
      project.getIgnoredFilePatterns().loadFromString(myIgnorePatterns);
      return project;
    }
    finally {
      final long loadTime = System.currentTimeMillis() - start;
      LOG.info("Project " + projectPath + " loaded in " + loadTime + " ms");
    }
  }

  private void initSdksAndGlobalLibraries(Project project) {
    final MethodClosure fakeClosure = new MethodClosure(new Object(), "hashCode");
    for (GlobalLibrary library : myGlobalLibraries) {
      if (library instanceof SdkLibrary) {
        final SdkLibrary sdk = (SdkLibrary)library;
        Node additionalData = null;
        final String additionalXml = sdk.getAdditionalDataXml();
        if (additionalXml != null) {
          try {
            additionalData = new XmlParser(false, false).parseText(additionalXml);
          }
          catch (Exception e) {
            LOG.info(e);
          }
        }
        final Sdk jdk = project.createSdk(sdk.getTypeName(), sdk.getName(), sdk.getVersion(), sdk.getHomePath(), additionalData);
        if (jdk != null) {
          jdk.setClasspath(sdk.getPaths());
        }
        else {
          LOG.info("Failed to load SDK " + sdk.getName() + ", type: " + sdk.getTypeName());
        }
      }
      else {
        final Library lib = project.createGlobalLibrary(library.getName(), fakeClosure);
        if (lib != null) {
          lib.setClasspath(library.getPaths());
        }
        else {
          LOG.info("Failed to load global library " + library.getName());
        }
      }
    }
  }

  private static boolean isDirectoryBased(File projectFile) {
    return !(projectFile.isFile() && projectFile.getName().endsWith(".ipr"));
  }

  private static CompileScope createCompilationScope(BuildType buildType,
                                                     ProjectDescriptor pd,
                                                     final Timestamps timestamps, Set<String> modules,
                                                     Collection<String> artifactNames,
                                                     Collection<String> paths) throws Exception {
    Set<Artifact> artifacts = new HashSet<Artifact>();
    if (artifactNames.isEmpty() && buildType == BuildType.PROJECT_REBUILD) {
      artifacts.addAll(pd.project.getArtifacts().values());
    }
    else {
      final Map<String, Artifact> artifactMap = pd.project.getArtifacts();
      for (String name : artifactNames) {
        final Artifact artifact = artifactMap.get(name);
        if (artifact != null && !StringUtil.isEmpty(artifact.getOutputPath())) {
          artifacts.add(artifact);
        }
      }
    }

    final CompileScope compileScope;
    if (buildType == BuildType.PROJECT_REBUILD || (modules.isEmpty() && paths.isEmpty())) {
      compileScope = new AllProjectScope(pd.project, artifacts, buildType != BuildType.MAKE);
    }
    else {
      final Set<Module> forcedModules;
      if (!modules.isEmpty()) {
        forcedModules = new HashSet<Module>();
        for (Module m : pd.project.getModules().values()) {
          if (modules.contains(m.getName())) {
            forcedModules.add(m);
          }
        }
      }
      else {
        forcedModules = Collections.emptySet();
      }

      final Map<String, Set<File>> filesToCompile;
      if (!paths.isEmpty()) {
        filesToCompile = new HashMap<String, Set<File>>();
        for (String path : paths) {
          final File file = new File(path);
          final RootDescriptor rd = pd.rootsIndex.getModuleAndRoot(file);
          if (rd != null) {
            Set<File> files = filesToCompile.get(rd.module);
            if (files == null) {
              files = new HashSet<File>();
              filesToCompile.put(rd.module, files);
            }
            files.add(file);
            if (buildType == BuildType.FORCED_COMPILATION) {
              pd.fsState.markDirty(file, rd, timestamps);
            }
          }
        }
      }
      else {
        filesToCompile = Collections.emptyMap();
      }

      if (filesToCompile.isEmpty()) {
        compileScope = new ModulesScope(pd.project, forcedModules, artifacts, buildType != BuildType.MAKE);
      }
      else {
        compileScope = new ModulesAndFilesScope(pd.project, forcedModules, filesToCompile, artifacts, buildType != BuildType.MAKE);
      }
    }
    return compileScope;
  }


  private static BuildType convertCompileType(CmdlineRemoteProto.Message.ControllerMessage.ParametersMessage.Type compileType) {
    switch (compileType) {
      case CLEAN: return BuildType.CLEAN;
      case MAKE: return BuildType.MAKE;
      case REBUILD: return BuildType.PROJECT_REBUILD;
      case FORCED_COMPILATION: return BuildType.FORCED_COMPILATION;
    }
    return BuildType.MAKE; // use make by default
  }

  private static class EventsProcessor extends SequentialTaskExecutor {
    private final AtomicBoolean myProcessingEnabled = new AtomicBoolean(false);

    EventsProcessor() {
      super(SharedThreadPool.INSTANCE);
    }

    public void startProcessing() {
      if (!myProcessingEnabled.getAndSet(true)) {
        super.processQueue();
      }
    }

    @Override
    protected void processQueue() {
      if (myProcessingEnabled.get()) {
        super.processQueue();
      }
    }
  }

  private class ConstantSearch implements Callbacks.ConstantAffectionResolver {
    @Nullable @Override
    public Future<Callbacks.ConstantAffection> request(String ownerClassName, String fieldName, int accessFlags, boolean fieldRemoved, boolean accessChanged) {
      final CmdlineRemoteProto.Message.BuilderMessage.ConstantSearchTask.Builder task =
        CmdlineRemoteProto.Message.BuilderMessage.ConstantSearchTask.newBuilder();
      task.setOwnerClassName(ownerClassName);
      task.setFieldName(fieldName);
      task.setAccessFlags(accessFlags);
      task.setIsAccessChanged(accessChanged);
      task.setIsFieldRemoved(fieldRemoved);
      final ConstantSearchFuture future = new ConstantSearchFuture();
      final ConstantSearchFuture prev = mySearchTasks.put(new Pair<String, String>(ownerClassName, fieldName), future);
      if (prev != null) {
        prev.setDone();
      }
      Channels.write(myChannel,
        CmdlineProtoUtil.toMessage(
          mySessionId, CmdlineRemoteProto.Message.BuilderMessage.newBuilder().setType(CmdlineRemoteProto.Message.BuilderMessage.Type.CONSTANT_SEARCH_TASK).setConstantSearchTask(task.build()).build()
        )
      );
      return future;
    }
  }

  private static class ConstantSearchFuture extends BasicFuture<Callbacks.ConstantAffection> {
    private volatile Callbacks.ConstantAffection myResult = Callbacks.ConstantAffection.EMPTY;

    private ConstantSearchFuture() {
    }

    public void setResult(final Collection<File> affectedFiles) {
      myResult = new Callbacks.ConstantAffection(affectedFiles);
      setDone();
    }

    @Override
    public Callbacks.ConstantAffection get() throws InterruptedException, ExecutionException {
      super.get();
      return myResult;
    }

    @Override
    public Callbacks.ConstantAffection get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
      super.get(timeout, unit);
      return myResult;
    }
  }
}
