package com.adrninistrator.jacg.runner.base;

import com.adrninistrator.jacg.common.Constants;
import com.adrninistrator.jacg.common.DC;
import com.adrninistrator.jacg.dto.MultiCallInfo;
import com.adrninistrator.jacg.dto.NoticeCallInfo;
import com.adrninistrator.jacg.enums.CallTypeEnum;
import com.adrninistrator.jacg.runner.RunnerGenAllGraph4Callee;
import com.adrninistrator.jacg.util.CommonUtil;
import com.adrninistrator.jacg.util.FileUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * @author adrninistrator
 * @date 2021/6/18
 * @description:
 */

public abstract class AbstractRunnerGenCallGraph extends AbstractRunner {

    private static final Logger logger = LoggerFactory.getLogger(AbstractRunnerGenCallGraph.class);

    // 配置文件中指定的需要处理的任务
    protected Set<String> taskSet;

    // 保存当前生成输出文件时的目录前缀
    protected String outputDirPrefix;

    /*
        方法注解信息
        key: 方法HASH+长度
        value: 所有注解排序后拼接，分隔符为半角逗号,
     */
    protected Map<String, String> methodAnnotationsMap = new HashMap<>(100);

    /*
        接口调用对应实现类的方法调用
        key: 接口方法
        value: 实现类方法
     */
    private Map<String, MultiCallInfo> itfMethodCallMap = new HashMap<>();

    /*
        抽象父类调用对应子类的方法调用
        key: 抽象父类方法
        value: 子类方法
     */
    private Map<String, MultiCallInfo> sccMethodCallMap = new HashMap<>();

    // 接口调用对应实现类的方法调用，存在一对多的接口
    private Set<String> itfMultiCallerMethodSet = new TreeSet<>();

    // 抽象父类调用对应子类的方法调用，存在一对多的抽象父类
    private Set<String> sccMultiCallerMethodSet = new TreeSet<>();

    /*
        被禁用的接口调用对应实现类的方法调用
        key: 接口方法
        value: 实现类方法
     */
    private Map<String, MultiCallInfo> disabledItfMethodCallMap = new TreeMap<>();

    /*
        被禁用的抽象父类调用对应子类的方法调用
        key: 抽象父类方法
        value: 子类方法
     */
    private Map<String, MultiCallInfo> disabledSccMethodCallMap = new TreeMap<>();

    /**
     * 获取简单类名
     *
     * @param className
     * @return null: 未获取到，非null: 若不存在同名类，则返回简单类名；若存在同名类，则返回完整类名
     */
    protected String getSimpleClassName(String className) {
        if (className.contains(Constants.FLAG_DOT)) {
            // 完整类名，查找对应的简单类名
            String sql = sqlCacheMap.get(Constants.SQL_KEY_CN_QUERY_SIMPLE_CLASS);
            if (sql == null) {
                sql = "select " + DC.CN_SIMPLE_NAME + " from " + Constants.TABLE_PREFIX_CLASS_NAME + confInfo.getAppName() +
                        " where " + DC.CN_FULL_NAME + " = ?";
                cacheSql(Constants.SQL_KEY_CN_QUERY_SIMPLE_CLASS, sql);
            }

            List<Object> list = dbOperator.queryListOneColumn(sql, new Object[]{className});
            if (CommonUtil.isCollectionEmpty(list)) {
                logger.error("指定的完整类名不存在，请检查 {}", className);
                return null;
            }
            return (String) list.get(0);
        }

        // 简单类名
        String sql = sqlCacheMap.get(Constants.SQL_KEY_CN_QUERY_FULL_CLASS);
        if (sql == null) {
            sql = "select " + DC.CN_SIMPLE_NAME + " from " + Constants.TABLE_PREFIX_CLASS_NAME + confInfo.getAppName() +
                    " where " + DC.CN_SIMPLE_NAME + " = ?";
            cacheSql(Constants.SQL_KEY_CN_QUERY_FULL_CLASS, sql);
        }

        List<Object> list = dbOperator.queryListOneColumn(sql, new Object[]{className});
        if (CommonUtil.isCollectionEmpty(list)) {
            logger.error("指定的类名不存在，请检查是否需要使用完整类名形式 {}", className);
            return null;
        }
        return (String) list.get(0);
    }

    // 从方法调用关系表查询指定的类是否存在
    protected boolean checkClassNameExists(String className) {
        /*
            对于 select xxx limit n union all select xxx limit n 写法
            10.0.10-MariaDB-V2.0R132D001-20170821-1540 版本允许
            10.1.9-MariaDBV1.0R012D003-20180427-1600 版本不允许
            因此使用 select xxx from ((select xxx limit n) union all (select xxx limit n)) as a的写法
         */
        String sql = sqlCacheMap.get(Constants.SQL_KEY_MC_QUERY_CLASS_EXISTS);
        if (sql == null) {
            sql = "select class_name from ((select " + DC.MC_CALLER_CLASS_NAME + " as class_name from " + Constants.TABLE_PREFIX_METHOD_CALL + confInfo.getAppName() +
                    " where " + DC.MC_CALLER_CLASS_NAME + " = ? limit 1) union all (select " + DC.MC_CALLEE_CLASS_NAME + " as class_name " +
                    "from " + Constants.TABLE_PREFIX_METHOD_CALL + confInfo.getAppName() +
                    " where " + DC.MC_CALLEE_CLASS_NAME + " = ? limit 1)) as az";
            cacheSql(Constants.SQL_KEY_MC_QUERY_CLASS_EXISTS, sql);
        }

        List<Object> list = dbOperator.queryListOneColumn(sql, new Object[]{className, className});
        if (list == null) {
            return false;
        }

        if (list.isEmpty()) {
            logger.error("指定的类从调用关系表中未查询到 {}", className);
            return false;
        }

        return true;
    }

    // 读取方法注解
    protected boolean readMethodAnnotation() {
        logger.info("读取方法注解");
        String sql = sqlCacheMap.get(Constants.SQL_KEY_MA_QUERY_METHOD_ANNOTATION);
        if (sql == null) {
            String columns = StringUtils.join(Constants.TABLE_COLUMNS_METHOD_ANNOTATION, Constants.FLAG_COMMA_WITH_SPACE);
            sql = "select " + columns + " from " + Constants.TABLE_PREFIX_METHOD_ANNOTATION + confInfo.getAppName() +
                    " order by " + DC.MA_METHOD_HASH + ", " + DC.MA_ANNOTATION_NAME;
            cacheSql(Constants.SQL_KEY_MA_QUERY_METHOD_ANNOTATION, sql);
        }

        List<Map<String, Object>> list = dbOperator.queryList(sql, null);
        if (list == null) {
            return false;
        }

        if (list.isEmpty()) {
            return true;
        }

        for (Map<String, Object> map : list) {
            String methodhash = (String) map.get(DC.MA_METHOD_HASH);
            String annotationName = (String) map.get(DC.MA_ANNOTATION_NAME);

            String existedAnnotationName = methodAnnotationsMap.get(methodhash);
            if (existedAnnotationName == null) {
                methodAnnotationsMap.put(methodhash, Constants.FLAG_AT + annotationName);
            } else {
                methodAnnotationsMap.put(methodhash, existedAnnotationName + Constants.FLAG_AT + annotationName);
            }
        }

        return true;
    }

    // 读取配置文件中指定的需要处理的任务
    protected boolean readTaskInfo(String taskFile) {
        taskSet = FileUtil.readFile2Set(taskFile);
        if (CommonUtil.isCollectionEmpty(taskSet)) {
            logger.error("读取文件不存在或内容为空 {}", taskFile);
            return false;
        }

        if (taskSet.size() < confInfo.getThreadNum()) {
            logger.info("将线程数修改为需要处理的任务数 {}", taskSet.size());
            confInfo.setThreadNum(taskSet.size());
        }

        return true;
    }

    // 创建输出文件所在目录
    protected boolean createOutputDit(String prefix) {
        outputDirPrefix = prefix + File.separator + CommonUtil.currentTime();
        logger.info("创建保存输出文件的目录 {}", outputDirPrefix);
        // 判断目录是否存在，不存在时尝试创建
        return FileUtil.isDirectoryExists(outputDirPrefix);
    }

    // 生成输出文件前缀，包含了当前方法的调用层级
    protected String genOutputPrefix(int level) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("[").append(level).append("]")
                .append(Constants.FLAG_HASHTAG)
                .append(CommonUtil.genOutputFlag(level));
        return stringBuilder.toString();
    }

    // 将输出文件合并
    protected void combineOutputFile(String fileName) {
        if (confInfo.isGenCombinedOutput()) {
            List<File> outputFileList = FileUtil.findFileInDir(outputDirPrefix, Constants.EXT_TXT);
            if (!CommonUtil.isCollectionEmpty(outputFileList) && outputFileList.size() > 1) {
                String combinedOutputFilePath = outputDirPrefix + File.separator + Constants.COMBINE_FILE_NAME_PREFIX + fileName + Constants.EXT_TXT;
                FileUtil.combineTextFile(combinedOutputFilePath, outputFileList);
            }
        }
    }

    // 根据调用关系ID获取用于提示的信息
    private NoticeCallInfo queryNoticeCallInfo(Integer id) {
        String sql = sqlCacheMap.get(Constants.SQL_KEY_MC_QUERY_NOTICE_INFO);
        if (sql == null) {
            String columns = StringUtils.join(new String[]{
                    DC.MC_CALLER_METHOD_HASH,
                    DC.MC_CALLER_FULL_METHOD,
                    DC.MC_CALLEE_FULL_METHOD
            }, Constants.FLAG_COMMA_WITH_SPACE);
            sql = "select " + columns + " from " + Constants.TABLE_PREFIX_METHOD_CALL + confInfo.getAppName() +
                    " where " + DC.MC_ID + " = ?";
            cacheSql(Constants.SQL_KEY_MC_QUERY_NOTICE_INFO, sql);
        }

        Map<String, Object> map = dbOperator.queryOneRow(sql, new Object[]{id});
        if (map == null || map.isEmpty()) {
            logger.error("查询需要提示的信息失败 {}", id);
            return null;
        }

        String callerMethodHash = (String) map.get(DC.MC_CALLER_METHOD_HASH);
        String callerFullMethod = (String) map.get(DC.MC_CALLER_FULL_METHOD);
        String calleeFullMethod = (String) map.get(DC.MC_CALLEE_FULL_METHOD);
        if (StringUtils.isAnyBlank(callerMethodHash, callerFullMethod, calleeFullMethod)) {
            logger.error("查询需要提示的信息存在空值 {}", id);
            return null;
        }

        NoticeCallInfo noticeCallInfo = new NoticeCallInfo();
        noticeCallInfo.setCallerMethodHash(callerMethodHash);
        noticeCallInfo.setCallerFullMethod(callerFullMethod);
        noticeCallInfo.setCalleeFullMethod(calleeFullMethod);
        return noticeCallInfo;
    }

    // 记录可能出现一对多的方法调用
    protected boolean recordMethodCallMayBeMulti(Integer id, String callType) {
        CallTypeEnum callTypeEnum = CallTypeEnum.getFromType(callType);
        if (callTypeEnum != CallTypeEnum.CTE_ITF && callTypeEnum != CallTypeEnum.CTE_SCC) {
            return true;
        }

        // 根据调用关系ID获取用于提示的信息
        NoticeCallInfo noticeCallInfo = queryNoticeCallInfo(id);
        if (noticeCallInfo == null) {
            return false;
        }

        Map<String, MultiCallInfo> methodCallMap;
        Set<String> multiCallerMethodSet;

        if (callTypeEnum == CallTypeEnum.CTE_ITF) {
            methodCallMap = itfMethodCallMap;
            multiCallerMethodSet = itfMultiCallerMethodSet;
        } else {
            methodCallMap = sccMethodCallMap;
            multiCallerMethodSet = sccMultiCallerMethodSet;
        }

        String callerMethodHash = noticeCallInfo.getCallerMethodHash();
        String callerFullMethod = noticeCallInfo.getCallerFullMethod();
        String calleeFullMethod = noticeCallInfo.getCalleeFullMethod();

        MultiCallInfo multiCallInfo = methodCallMap.get(callerFullMethod);
        if (multiCallInfo == null) {
            multiCallInfo = new MultiCallInfo();
            Set<String> calleeMethodSet = new TreeSet<>();
            multiCallInfo.setCalleeFullMethodSet(calleeMethodSet);
            multiCallInfo.setCallerMethodHash(callerMethodHash);

            methodCallMap.put(callerFullMethod, multiCallInfo);
        }

        Set<String> calleeMethodSet = multiCallInfo.getCalleeFullMethodSet();
        calleeMethodSet.add(calleeFullMethod);
        if (calleeMethodSet.size() > 1) {
            multiCallerMethodSet.add(callerFullMethod);
        }

        return true;
    }

    // 记录被禁用的方法调用
    protected boolean recordDisabledMethodCall(Integer id, String callType) {
        CallTypeEnum callTypeEnum = CallTypeEnum.getFromType(callType);
        if (callTypeEnum != CallTypeEnum.CTE_ITF && callTypeEnum != CallTypeEnum.CTE_SCC) {
            return true;
        }

        // 根据调用关系ID获取用于提示的信息
        NoticeCallInfo noticeCallInfo = queryNoticeCallInfo(id);
        if (noticeCallInfo == null) {
            return false;
        }

        Map<String, MultiCallInfo> methodCallMap;

        if (callTypeEnum == CallTypeEnum.CTE_ITF) {
            methodCallMap = disabledItfMethodCallMap;
        } else {
            methodCallMap = disabledSccMethodCallMap;
        }

        String callerMethodHash = noticeCallInfo.getCallerMethodHash();
        String callerFullMethod = noticeCallInfo.getCallerFullMethod();
        String calleeFullMethod = noticeCallInfo.getCalleeFullMethod();

        MultiCallInfo multiCallInfo = methodCallMap.get(callerFullMethod);
        if (multiCallInfo == null) {
            multiCallInfo = new MultiCallInfo();
            Set<String> calleeMethodSet = new TreeSet<>();
            multiCallInfo.setCalleeFullMethodSet(calleeMethodSet);
            multiCallInfo.setCallerMethodHash(callerMethodHash);

            methodCallMap.put(callerFullMethod, multiCallInfo);
        }
        Set<String> calleeMethodSet = multiCallInfo.getCalleeFullMethodSet();
        calleeMethodSet.add(calleeFullMethod);

        return true;
    }

    // 打印存在一对多的方法调用
    private void printMultiMethodCall(Map<String, MultiCallInfo> methodCallMap, Set<String> multiCallerMethodSet, CallTypeEnum callTypeEnum) {
        if (multiCallerMethodSet.isEmpty()) {
            logger.info("{} 不存在一对多的方法调用，不打印相关信息", callTypeEnum);
            return;
        }

        String filePath;
        if (CallTypeEnum.CTE_ITF == callTypeEnum) {
            filePath = outputDirPrefix + File.separator + Constants.NOTICE_MULTI_ITF_MD;
        } else {
            filePath = outputDirPrefix + File.separator + Constants.NOTICE_MULTI_SCC_MD;
        }

        logger.info("{} 存在一对多的方法调用，打印相关信息 {}", callTypeEnum, filePath);

        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath),
                StandardCharsets.UTF_8))) {
            stringBuilder.append("# 说明").append(Constants.NEW_LINE).append(Constants.NEW_LINE);

            if (CallTypeEnum.CTE_ITF == callTypeEnum) {
                stringBuilder.append("出现当前文件，说明接口调用对应实现类的方法调用存在一对多的方法调用");
            } else {
                stringBuilder.append("出现当前文件，说明抽象父类调用对应子类的方法调用存在一对多的方法调用");
            }
            stringBuilder.append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                    .append("可以使用以下SQL语句查找对应的方法调用并禁用，仅保留需要的调用关系").append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                    .append("```sql").append(Constants.NEW_LINE)
                    // 生成提示信息中的查询SQL
                    .append(genNoticeSelectSql(callTypeEnum.getType())).append(Constants.NEW_LINE)
                    // 生成提示信息中的更新为禁用SQL
                    .append(genNoticeUpdateDisableSql(callTypeEnum.getType())).append(Constants.NEW_LINE);
            if (this instanceof RunnerGenAllGraph4Callee) {
                // 生成向上的方法调用完整调用链时，增加一个显示的update语句
                stringBuilder.append(Constants.NEW_LINE).append(genNoticeSelectSql4Callee()).append(Constants.NEW_LINE)
                        .append(genNoticeUpdateDisableSql4Callee()).append(Constants.NEW_LINE);
            }
            stringBuilder.append("```");

            for (String multiCallerMethod : multiCallerMethodSet) {
                MultiCallInfo multiCallInfo = methodCallMap.get(multiCallerMethod);
                if (multiCallInfo == null || CommonUtil.isCollectionEmpty(multiCallInfo.getCalleeFullMethodSet())) {
                    logger.error("未查找到对应的一对多方法调用关系 {}", multiCallerMethod);
                    continue;
                }
                stringBuilder.append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                        .append("## ").append(multiCallerMethod).append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                        .append("- ").append(DC.MC_CALLER_METHOD_HASH).append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                        .append(multiCallInfo.getCallerMethodHash()).append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                        .append("- ").append(DC.MC_CALLEE_FULL_METHOD).append("（被调用的方法）").append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                        .append("```").append(Constants.NEW_LINE);
                for (String calleeMethod : multiCallInfo.getCalleeFullMethodSet()) {
                    stringBuilder.append(calleeMethod).append(Constants.NEW_LINE);
                }
                stringBuilder.append("```");

                // 打印存在一对多的方法调用，自定义处理
                printMultiMethodCallCustom(multiCallInfo.getCallerMethodHash(), stringBuilder);
            }

            out.write(stringBuilder.toString());
        } catch (Exception e) {
            logger.error("error ", e);
        }
    }

    // 打印存在一对多的方法调用，自定义处理
    protected void printMultiMethodCallCustom(String callerMethodHash, StringBuilder stringBuilder) {
    }

    // 打印被禁用的方法调用
    private void printDisabledMethodCall(Map<String, MultiCallInfo> disabledMethodCallMap, CallTypeEnum callTypeEnum) {
        if (disabledMethodCallMap.isEmpty()) {
            logger.info("{} 不存在被禁用的方法调用，不打印相关信息", callTypeEnum);
            return;
        }

        String filePath;
        if (CallTypeEnum.CTE_ITF == callTypeEnum) {
            filePath = outputDirPrefix + File.separator + Constants.NOTICE_DISABLED_ITF_MD;
        } else {
            filePath = outputDirPrefix + File.separator + Constants.NOTICE_DISABLED_SCC_MD;
        }

        logger.info("{} 存在被禁用的方法调用，打印相关信息 {}", callTypeEnum, filePath);

        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath),
                StandardCharsets.UTF_8))) {
            stringBuilder.append("# 说明").append(Constants.NEW_LINE).append(Constants.NEW_LINE);

            if (CallTypeEnum.CTE_ITF == callTypeEnum) {
                stringBuilder.append("出现当前文件，说明接口调用对应实现类的方法调用存在被禁用的方法调用");
            } else {
                stringBuilder.append("出现当前文件，说明抽象父类调用对应子类的方法调用存在被禁用的方法调用");
            }
            stringBuilder.append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                    .append("可以使用以下SQL语句查找对应的方法调用并启用").append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                    .append("```sql").append(Constants.NEW_LINE)
                    // 生成提示信息中的查询SQL
                    .append(genNoticeSelectSql(callTypeEnum.getType())).append(Constants.NEW_LINE)
                    // 生成提示信息中的更新为禁用SQL
                    .append(genNoticeUpdateEnableSql(callTypeEnum.getType())).append(Constants.NEW_LINE)
                    .append("```");

            for (Map.Entry<String, MultiCallInfo> entry : disabledMethodCallMap.entrySet()) {
                String disabledCallerMethod = entry.getKey();

                MultiCallInfo multiCallInfo = entry.getValue();
                if (multiCallInfo == null || CommonUtil.isCollectionEmpty(multiCallInfo.getCalleeFullMethodSet())) {
                    logger.error("未查找到对应的被禁用方法调用关系 {}", disabledCallerMethod);
                    continue;
                }
                stringBuilder.append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                        .append("## ").append(disabledCallerMethod).append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                        .append("- ").append(DC.MC_CALLER_METHOD_HASH).append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                        .append(multiCallInfo.getCallerMethodHash()).append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                        .append("- ").append(DC.MC_CALLEE_FULL_METHOD).append("（被调用的方法）").append(Constants.NEW_LINE).append(Constants.NEW_LINE)
                        .append("```").append(Constants.NEW_LINE);
                for (String calleeMethod : multiCallInfo.getCalleeFullMethodSet()) {
                    stringBuilder.append(calleeMethod).append(Constants.NEW_LINE);
                }
                stringBuilder.append("```");
            }

            out.write(stringBuilder.toString());
        } catch (Exception e) {
            logger.error("error ", e);
        }
    }

    // 打印提示信息
    protected void printNoticeInfo() {
        printMultiMethodCall(itfMethodCallMap, itfMultiCallerMethodSet, CallTypeEnum.CTE_ITF);
        printMultiMethodCall(sccMethodCallMap, sccMultiCallerMethodSet, CallTypeEnum.CTE_SCC);
        printDisabledMethodCall(disabledItfMethodCallMap, CallTypeEnum.CTE_ITF);
        printDisabledMethodCall(disabledSccMethodCallMap, CallTypeEnum.CTE_SCC);
    }

    // 生成提示信息中的查询SQL
    private String genNoticeSelectSql(String callType) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("select * from ")
                .append(Constants.TABLE_PREFIX_METHOD_CALL).append(confInfo.getAppName())
                .append(" where ").append(DC.MC_CALL_TYPE).append(" = '").append(callType)
                .append("' and ").append(DC.MC_CALLER_METHOD_HASH).append(" = '';");
        return stringBuilder.toString();
    }

    private String genNoticeSelectSql4Callee() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("select * from ")
                .append(Constants.TABLE_PREFIX_METHOD_CALL).append(confInfo.getAppName())
                .append(" where ").append(DC.MC_CALLEE_METHOD_HASH).append(" = '';");
        return stringBuilder.toString();
    }

    // 生成提示信息中的更新为禁用SQL
    private String genNoticeUpdateDisableSql(String callType) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("update ")
                .append(Constants.TABLE_PREFIX_METHOD_CALL).append(confInfo.getAppName())
                .append(" set ").append(DC.MC_ENABLED)
                .append(" = ").append(Constants.DISABLED)
                .append(" where ")
                .append(DC.MC_CALL_TYPE).append(" = '").append(callType)
                .append("' and ").append(DC.MC_CALLER_METHOD_HASH).append(" = '' and ")
                .append(DC.MC_CALLEE_FULL_METHOD).append(" <> '';");
        return stringBuilder.toString();
    }

    private String genNoticeUpdateDisableSql4Callee() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("update ")
                .append(Constants.TABLE_PREFIX_METHOD_CALL).append(confInfo.getAppName())
                .append(" set ").append(DC.MC_ENABLED)
                .append(" = ").append(Constants.DISABLED)
                .append(" where ").append(DC.MC_CALLEE_METHOD_HASH).append(" = '' and ")
                .append(DC.MC_CALLER_FULL_METHOD).append(" <> '';");
        return stringBuilder.toString();
    }

    // 生成提示信息中的更新为启用SQL
    private String genNoticeUpdateEnableSql(String callType) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("update ")
                .append(Constants.TABLE_PREFIX_METHOD_CALL).append(confInfo.getAppName())
                .append(" set ").append(DC.MC_ENABLED)
                .append(" = ").append(Constants.ENABLED)
                .append(" where ")
                .append(DC.MC_CALL_TYPE).append(" = '").append(callType)
                .append("' and ").append(DC.MC_CALLER_METHOD_HASH).append(" = '' and ")
                .append(DC.MC_CALLEE_FULL_METHOD).append(" = '';");
        return stringBuilder.toString();
    }

    /**
     * 检查Jar包文件是否有更新
     *
     * @return true: 有更新，false: 没有更新
     */
    protected boolean checkJarFileUpdated() {
        if (StringUtils.isBlank(confInfo.getCallGraphJarList())) {
            return false;
        }

        Map<String, Map<String, Object>> jarInfoMap = queryJarFileInfo();
        if (jarInfoMap == null) {
            return false;
        }

        logger.info("检查Jar包文件是否有更新 {}", confInfo.getCallGraphJarList());

        String[] array = confInfo.getCallGraphJarList().split(Constants.FLAG_SPACE);
        for (String jarName : array) {
            if (FileUtil.isFileExists(jarName)) {
                String jarFilePath = FileUtil.getCanonicalPath(jarName);
                if (jarFilePath == null) {
                    logger.error("获取文件路径失败: {}", jarName);
                    return true;
                }

                String jarPathHash = CommonUtil.genHashWithLen(jarFilePath);
                Map<String, Object> jarInfo = jarInfoMap.get(jarPathHash);
                if (jarInfo == null) {
                    String jarFullPath = jarName.equals(jarFilePath) ? "" : jarFilePath;
                    logger.error("指定的Jar包未导入数据库中，请先执行 TestRunnerWriteDb 类导入数据库\n{} {}", jarName, jarFullPath);
                    return true;
                }

                long lastModified = FileUtil.getFileLastModified(jarFilePath);
                String lastModifiedStr = String.valueOf(lastModified);
                if (!lastModifiedStr.equals(jarInfo.get(DC.JI_LAST_MODIFIED))) {
                    String jarFileHash = FileUtil.getFileMd5(jarFilePath);
                    if (!jarFileHash.equals(jarInfo.get(DC.JI_JAR_HASH))) {
                        String jarFullPath = jarName.equals(jarFilePath) ? "" : jarFilePath;
                        logger.error("指定的Jar包文件内容有变化，请先执行 TestRunnerWriteDb 类导入数据库\n{} {} {}", new Date(lastModified), jarName, jarFullPath);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private Map<String, Map<String, Object>> queryJarFileInfo() {
        String sql = sqlCacheMap.get(Constants.SQL_KEY_JI_QUERY_JAR_INFO);
        if (sql == null) {
            sql = "select * from " + Constants.TABLE_PREFIX_JAR_INFO + confInfo.getAppName();
            cacheSql(Constants.SQL_KEY_JI_QUERY_JAR_INFO, sql);
        }

        List<Map<String, Object>> list = dbOperator.queryList(sql, new Object[]{});
        if (CommonUtil.isCollectionEmpty(list)) {
            logger.error("查询到Jar包信息为空");
            return null;
        }

        Map<String, Map<String, Object>> rtnMap = new HashMap<>(list.size());

        for (Map<String, Object> map : list) {
            String jarPathHash = (String) map.get(DC.JI_JAR_PATH_HASH);
            rtnMap.putIfAbsent(jarPathHash, map);
        }

        return rtnMap;
    }

    /**
     * 获取本次执行时的输出目录
     *
     * @return null: 执行失败，非null: 执行成功
     */
    public String getSuccessOutputDir() {
        if (!runSuccess) {
            return null;
        }
        return outputDirPrefix;
    }
}
