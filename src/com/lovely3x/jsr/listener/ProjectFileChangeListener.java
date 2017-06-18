package com.lovely3x.jsr.listener;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.*;
import com.intellij.openapi.vfs.ex.VirtualFileManagerEx;
import com.lovely3x.jsr.utils.StreamUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;

/**
 * Created by lovely3x on 2017/6/16.
 */
public class ProjectFileChangeListener implements ProjectComponent {


    public static final Key<ProjectFileChangeListener> USER_DATA_KEY_JSR_GENERATOR = Key.create("user.data.key.jsr");

    public static final Logger LOG = Logger.getInstance("#com.lovely3x.jsr.listener.ProjectFileChangeListener");

    private static final boolean DEBUG = true;

    public static final String COMPONENT_NAME = "COCOS_2D_JS_RESOURCE_GENERATOR";

    public static final String DEFAULT_TEMPLATE_RESOURCE_JS = "/resource.js";
    public static final String DEFAULT_TEMPLATE_RESOURCE_JS_NAME = "resource.js";

    public static final String JSR_JSON_FILE = "jsr.json";
    public static final String PROJECT_JSON_FILE = "project.json";

    private static final int CASE_SENSITIVE_UPPERCASE = 1;
    private static final int CASE_SENSITIVE_LOWERCASE = -1;
    private static final int CASE_SENSITIVE_UNSPECIFIED = 0;


    private final Project myProject;

    private static final String DEFAULT_RES_DIR = "res";
    private static final String DEFAULT_SRC_DIR = "src";
    private static final String DEFAULT_TEMPLATE_INDICATOR = "%%";

    private static final String DEFAULT_CONVERT_RE = "[/\\.\\-]";
    private static final String DEFAULT_CONVERT_VALUE = "_";

    private final String mAbsoluteJSRJSONFile;
    private final String mAbsoluteProjectJSONFile;

    /**
     * 需要生成的资源存放文件夹
     */
    private String resDir = DEFAULT_RES_DIR;

    /**
     * 源代码放置文件夹
     */
    private String srcDir = DEFAULT_SRC_DIR;
    /**
     * 模板文件
     */
    private String templateFile = null;
    /**
     * 模板文件的内容
     */
    private String templateFileContent = null;

    /**
     * 模板代码插入标识
     */
    private String templateIndicator = DEFAULT_TEMPLATE_INDICATOR;

    private String convertRe = DEFAULT_CONVERT_RE;
    private String convertValue = DEFAULT_CONVERT_VALUE;

    /**
     * 读取资源的文件位置(需要生成源代码的资源文件)
     */
    private static final String RES_DIR_KEY = "resourceDir";

    /**
     * 生成的源代码文件放置位置
     */
    private static final String SRC_DIR_KEY = "srcDir";

    /**
     * 用于获取生成源代码的模板文件的key
     */
    private static final String TEMPLATE_FILE_KEY = "templateFile";

    /***
     * 用于获取模板标识符(在生成源代码文件时,将会把模板文件中的标识符替换为生成的资源文件列表)
     */
    private static final String TEMPLATE_INDICATOR_KEY = "templateIndicator";

    /***
     * 用于替换时的正则表达式
     */
    private static final String CONVERT_RE_KEY = "convertRe";

    /***
     * 用于替换时的值
     */
    private static final String CONVERT_VALUE_KEY = "convertValue";

    /***
     * 将转换内容转换为小写的key
     */
    private static final String TO_LOWERCASE_KEY = "toLowercase";

    /***
     * 将转换内容转换为大写的key
     */
    private static final String TO_UPPERCASE_KEY = "toUppercase";


    private final VirtualFileListener mVirtualFileListener;

    private List<File> mProjectResourceFiles = new ArrayList<>();

    private File mResourceBaseFile;
    private String mResourceBasePath;


    private int caseSensitive = CASE_SENSITIVE_UPPERCASE;

    private boolean belongMyProjectResource(VirtualFile file) {
        return file.getPath().startsWith(mResourceBasePath);
    }

    private boolean shouldRegenerateFiles(VirtualFileEvent event) {
        VirtualFile file = event.getFile();
        if (!event.isFromRefresh()) {
            File lFile = new File(file.getPath());
            if (lFile.canRead() && !lFile.isHidden() && lFile.isFile()) {
                return belongMyProjectResource(file);
            }
        }
        return false;
    }

    protected ProjectFileChangeListener(Project project) {
        myProject = project;

        this.mAbsoluteJSRJSONFile = new File(myProject.getBasePath(), JSR_JSON_FILE).getAbsolutePath();
        this.mAbsoluteProjectJSONFile = new File(myProject.getBasePath(), PROJECT_JSON_FILE).getAbsolutePath();

        VirtualFileManager.getInstance().addVirtualFileListener(mVirtualFileListener = new VirtualFileAdapter() {
            @Override
            public void fileCopied(@NotNull VirtualFileCopyEvent event) {
                super.fileCopied(event);
            }

            @Override
            public void propertyChanged(@NotNull VirtualFilePropertyEvent event) {
                super.propertyChanged(event);
            }

            @Override
            public void contentsChanged(@NotNull VirtualFileEvent event) {
                super.contentsChanged(event);

                if (isDescriptionFile(event.getFile())) {
                    updateConfiguration();
                    generateSourceFile(false);
                }
            }

            @Override
            public void fileCreated(@NotNull VirtualFileEvent event) {
                super.fileCreated(event);

                if (isDescriptionFile(event.getFile())) {
                    updateConfiguration();
                    generateSourceFile(false);
                    return;
                }

                if (shouldRegenerateFiles(event)) {
                    generateSourceFile(true);
                }
            }

            @Override
            public void fileDeleted(@NotNull VirtualFileEvent event) {
                super.fileDeleted(event);

                if (isDescriptionFile(event.getFile())) {
                    updateConfiguration();
                    generateSourceFile(false);
                    return;
                }

                if (shouldRegenerateFiles(event)) {
                    generateSourceFile(true);
                }
            }

            @Override
            public void fileMoved(@NotNull VirtualFileMoveEvent event) {
                super.fileMoved(event);
                if (shouldRegenerateFiles(event)) {
                    generateSourceFile(true);
                }
            }
        });

        project.putUserData(USER_DATA_KEY_JSR_GENERATOR, this);
    }

    /**
     * 更新jsr的配置文件
     */
    public void updateConfiguration() {
        //todo 后期可以考虑一下就把这些全读完。
        //而不用每次都需要去打开一次文件
        updateResDir();//更新资源文件放置
        updateSrcDir();//更新源代码放置
        updateBasePath();//更新基础路径
        updateTemplateFile();//更新模板文件
        updateTemplateIndicator();//更新模板标识符
        updateConvertRule();//更新转换规则
        updateConvertValue();//更新转换值
        updateCaseSensitive();//更新大小写转换规则
    }

    /**
     * 更新大小写处理
     */
    private void updateCaseSensitive() {
        Boolean toLowercase = Boolean.valueOf(findConfig(TO_LOWERCASE_KEY, null));
        Boolean toUppercase = Boolean.valueOf(findConfig(TO_UPPERCASE_KEY, null));
        if (toLowercase && toUppercase) {
            throw new IllegalArgumentException("jsr描述 toLowercase 和 toUppercase 仅能同时存在其中一个。");
        }

        if (toLowercase) {
            caseSensitive = CASE_SENSITIVE_LOWERCASE;
        } else if (toUppercase) {
            caseSensitive = CASE_SENSITIVE_UPPERCASE;
        } else {
            caseSensitive = CASE_SENSITIVE_UNSPECIFIED;
        }
    }

    /***
     * 更新转换的正则表达式
     */
    private void updateConvertRule() {
        convertRe = findConfig(CONVERT_RE_KEY, DEFAULT_CONVERT_RE);
    }

    /***
     * 更新转转值
     */
    private void updateConvertValue() {
        convertValue = findConfig(CONVERT_VALUE_KEY, DEFAULT_CONVERT_VALUE);
    }


    /**
     * 更新需要处理的文件的基础路径,
     * 在调用之前应该总是先调用 {@link #updateResDir()}
     */
    private void updateBasePath() {
        String projectBasePath = myProject.getBasePath();
        this.mResourceBaseFile = new File(projectBasePath, resDir);
        this.mResourceBasePath = mResourceBaseFile.getAbsolutePath();
    }


    /**
     * 更新资源文件夹
     * 配置查找顺序 project.json -> jsr.json -> default
     */
    private void updateResDir() {
        resDir = findConfig(RES_DIR_KEY, DEFAULT_RES_DIR);
    }

    /**
     * 更新源代码放置文件夹
     * 配置查找顺序 project.json -> jsr.json -> default
     */
    private void updateSrcDir() {
        srcDir = findConfig(SRC_DIR_KEY, DEFAULT_SRC_DIR);
    }

    /**
     * 更新模板文件
     * 配置查找顺序 project.json -> jsr.json -> default
     */
    private void updateTemplateFile() {
        templateFile = findConfig(TEMPLATE_FILE_KEY, null);
        File f = null;
        if (templateFile != null && (f = new File(myProject.getBasePath(), templateFile)).exists()) {
            templateFileContent = StreamUtils.readToString(f);
        } else {
            //读取默认的模板内容
            InputStream resourceAsStream = ProjectFileChangeListener.class.getResourceAsStream(DEFAULT_TEMPLATE_RESOURCE_JS);
            templateFileContent = StreamUtils.readToString(resourceAsStream);
            StreamUtils.close(resourceAsStream);
        }
    }

    /**
     * 更新模板标识
     * 配置查找顺序 project.json -> jsr.json -> default
     */
    private void updateTemplateIndicator() {
        templateIndicator = findConfig(TEMPLATE_INDICATOR_KEY, DEFAULT_TEMPLATE_INDICATOR);
    }


    /**
     * 按照 project.json -> jsr.json -> default 这个顺序查找配置 给定的key对应的值,
     * 如果没有查找到,就返回给定的默认值
     *
     * @param key          需要查找的key
     * @param defaultValue 默认值
     * @return 根据key查找的值
     */
    private String findConfig(String key, String defaultValue) {
        //首选位置
        File cocosProjectDescJson = new File(myProject.getBasePath(), PROJECT_JSON_FILE);

        if (cocosProjectDescJson.exists() && cocosProjectDescJson.canRead()) {
            String dir = findMatchedValue(cocosProjectDescJson, key);
            if ((dir != null && dir.trim().length() > 0)) {
                return dir;
            }
        }

        //其次位置
        File projectRootDescJson = new File(myProject.getBasePath(), JSR_JSON_FILE);
        String dir = findMatchedValue(projectRootDescJson, key);
        if ((dir != null && dir.trim().length() > 0)) {
            return dir;
        }

        return defaultValue;
    }


    @Nullable
    private String findMatchedValue(File jsonFile, String key) {
        if (jsonFile == null || !jsonFile.exists()) return null;
        try {
            JsonParser parser = new JsonParser();
            JsonReader jr = new JsonReader(new FileReader(jsonFile));
            JsonElement element = parser.parse(jr);

            JsonObject jo = element.getAsJsonObject();
            if (jo.has(key)) {
                element = jo.get(key);
                return element.getAsString();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 生成源码文件
     *
     * @param reScanFiles 在生成源码前,是否需要先扫描文件
     */
    public void generateSourceFile(boolean reScanFiles) {
        if (reScanFiles) scanResources(false);

        try {

            HashMap<String, String> resMap = new HashMap<>();

            mProjectResourceFiles.forEach(file -> {
                //base /home/app/cocosProject
                //res /home/app/cocosProject/res
                //resource /home/cocosProject/res/some.png

                String resAbsolutePath = file.getAbsolutePath();
                int index = resAbsolutePath.indexOf(mResourceBasePath);
                if (index != -1) {
                    String relativePath = resAbsolutePath.substring(index + mResourceBasePath.length() - resDir.length());
                    String formatName = convertFilePath2FormatName(relativePath);
                    log(formatName);
                    if (resMap.containsKey(formatName)) {
                        throw new IllegalArgumentException(
                                String.format(Locale.US, "资源名重复 [%s],重复的文件是[%s]和[%s],请尝试修改文件名后重新生成。",
                                        formatName,
                                        resMap.get(formatName),
                                        relativePath));
                    } else {
                        resMap.put(formatName, relativePath);
                    }
                }
            });

            @NotNull String projectBasePath = myProject.getBasePath();


            String content = templateFileContent.replaceFirst(templateIndicator, convertMapToJSObject(resMap));

            File file = new File(new File(projectBasePath, srcDir),
                    templateFile == null ? DEFAULT_TEMPLATE_RESOURCE_JS_NAME : new File(templateFile).getName());

            FileWriter fWriter = new FileWriter(file);

            fWriter.write(content);
            fWriter.close();
            VirtualFileManagerEx.getInstance()
                    .refreshAndFindFileByUrl("file://" + file.getAbsolutePath());
            log("Source file generated at " + file);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 转换Map到JS源码中的JS对象
     *
     * @param map
     * @return
     */
    private String convertMapToJSObject(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        sb.append('{').append('\n');
        map.forEach((key, value) -> {
            sb.append('\t').append(key).append(' ').append(':').append(' ').append('"').append(value).append('"').append(',');
            sb.append('\n');
        });

        if (!map.isEmpty()) {
            sb.delete(sb.length() - 2, sb.length());
        }

        sb.append('\n').append('}');

        return sb.toString();
    }


    /**
     * 将文件路径转换为js变量名
     *
     * @param relativePath 需要转换的路径
     * @return 转换后的规则
     */
    private String convertFilePath2FormatName(String relativePath) {
        String formatted = relativePath.replaceAll(convertRe, convertValue);

        switch (caseSensitive) {
            case CASE_SENSITIVE_LOWERCASE:
                formatted = formatted.toLowerCase();
                break;
            case CASE_SENSITIVE_UPPERCASE:
                formatted = formatted.toUpperCase();
                break;
        }

        return formatted;
    }


    /***
     * 判断是否是jsr文件
     *
     * @param virtualFile 需要判断的文件
     * @return true or false
     */
    private boolean isJsrFile(VirtualFile virtualFile) {
        return mAbsoluteJSRJSONFile.equals(virtualFile.getPath());
    }

    /***
     * 判断是否是是描述文件,描述文件就是会影响jsr执行的文件
     * 换句话讲也就是是否是jsr.json 或 project.json 文件
     *
     * @param virtualFile 需要判断的文件
     * @return true or false
     */
    private boolean isDescriptionFile(VirtualFile virtualFile) {
        return isJsrFile(virtualFile) || isCocosProjectFile(virtualFile);
    }

    /***
     * 是否是cocos项目描述文件
     *
     * @param virtualFile
     * @return
     */
    private boolean isCocosProjectFile(VirtualFile virtualFile) {
        return mAbsoluteProjectJSONFile.equals(virtualFile.getPath());
    }


    @Override
    public void projectOpened() {
        updateConfiguration();
        scanResources(true);
        generateSourceFile(false);
    }

    /**
     * 扫描需要生成源代码的资源
     *
     * @param first 是否是第一次扫描
     */
    private void scanResources(boolean first) {
        if (!first) {
            //移除掉不存在的文件
            Iterator<File> it = mProjectResourceFiles.iterator();
            while (it.hasNext()) {
                File next = it.next();
                if (!next.exists()) {
                    it.remove();
                }
            }
        }

        FileUtil.visitFiles(mResourceBaseFile, file -> {
            if (file.exists() && file.isFile() && !file.isHidden()) {
                if (!mProjectResourceFiles.contains(file)) mProjectResourceFiles.add(file);
            }
            return true;
        });
    }

    @Override
    public void projectClosed() {
        log("Project closed");
    }

    @Override
    public void initComponent() {
        log("initComponent");
    }

    @Override
    public void disposeComponent() {
        VirtualFileManager.getInstance().removeVirtualFileListener(mVirtualFileListener);
    }

    @NotNull
    @Override
    public String getComponentName() {
        return COMPONENT_NAME;
    }

    private void log(String msg) {
        if (DEBUG) {
            LOG.debug(msg);
        }
    }

}
