package com.mhz.mvcframework.servlet;

import com.mhz.mvcframework.annotations.MyAutowired;
import com.mhz.mvcframework.annotations.MyController;
import com.mhz.mvcframework.annotations.MyRequestMapping;
import com.mhz.mvcframework.annotations.MyService;
import com.mhz.mvcframework.pojo.Handler;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class MyDispatcherServlet extends HttpServlet {

    private Properties properties = new Properties();

    private List<String> classNames = new ArrayList<>(); // 缓存扫描到的类的全限定类名

    // ioc容器
    private Map<String,Object> ioc = new HashMap<String,Object>();

    // handlerMapping
    //private Map<String,Method> handlerMapping = now HashMap<>(); // 存储url和Method之间的映射关系
    private List<Handler> handlerMapping = new ArrayList<>();


    @Override
    public void init(ServletConfig config) throws ServletException {

        //1.加载配置文件
        String contextConfigLocation = config.getInitParameter("contextConfigLocation");
        doLoadConfig(contextConfigLocation);

        //2.扫描相关类，扫描注解
        doScan(properties.getProperty("scanPackage"));

        //3.初始化bean对象，放入ioc容器
        doInstance();

        //4.实现依赖注入
        doAutoWired();

        //5.构造HandleMapping处理器映射器，将配置好的url和Method建立好关系
        initHandleMapping();

        System.out.println("自定义mvc初始化完毕");
    }

    private void initHandleMapping() {
        if (ioc.isEmpty())return;
        for (Map.Entry<String, Object> entry:ioc.entrySet()) {
            //遍历ioc中的class类型
            Class<?> aClass = entry.getValue().getClass();
            if (!aClass.isAnnotationPresent(MyController.class)){
                continue;
            }

            String baseUrl = "";
            if (aClass.isAnnotationPresent(MyRequestMapping.class)){
                MyRequestMapping annotation = aClass.getAnnotation(MyRequestMapping.class);
                baseUrl = annotation.value();
            }

            //获取方法
            Method[] methods = aClass.getMethods();
            for (int i = 0; i < methods.length; i++) {
                Method method = methods[i];

                //方法没有表示RequestMapping 则不做处理
                if(!method.isAnnotationPresent(MyRequestMapping.class))return;

                //如果标记 就处理
                MyRequestMapping annotation = method.getAnnotation(MyRequestMapping.class);
                String methodUrl = annotation.value();
                String url = baseUrl + methodUrl;

                //把method所有信息及url封装成一个handle
                Handler handler = new Handler(entry.getValue(), method, Pattern.compile(url));

                //计算方法中的参数位置信息
                Parameter[] parameters = method.getParameters();
                for (int j = 0; j < parameters.length; j++) {
                    Parameter parameter = parameters[j];

                    if (HttpServletRequest.class == parameter.getType() || HttpServletResponse.class == parameter.getType()) {
                        //如果是request和response对象，那么参数名称写HttpServletRequest和HttpServletResponse
                        handler.getParamIndexMapping().put(parameter.getType().getSimpleName(), j);
                    }else {
                        handler.getParamIndexMapping().put(parameter.getName(), j);
                    }
                }

                handlerMapping.add(handler);
            }

        }


    }

    //实现依赖注入
    private void doAutoWired() {
        if(ioc.isEmpty())return;

        //有对象，再进行依赖注入处理
        //遍历ioc对象，查看对象字段，是否有autowired注解，如有需要需维护依赖关系
        for (Map.Entry<String, Object> entry:ioc.entrySet()) {
            //获取bean对象中的字段信息
            Field[] declaredFields = entry.getValue().getClass().getDeclaredFields();

            for (int i = 0; i < declaredFields.length; i++) {
                Field declaredField = declaredFields[i];
                if (!declaredField.isAnnotationPresent(MyAutowired.class)){
                    continue;
                }

                //有该注解
                MyAutowired annotation = declaredField.getAnnotation(MyAutowired.class);
                String beanName = annotation.value();
                if ("".equals(beanName.trim())){
                    //没有配置具体的bean id，那就需要根据当前字段类型注入
                    beanName = declaredField.getType().getName();
                }

                //开启赋值
                declaredField.setAccessible(true);

                try {
                    declaredField.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }

    }

    //ioc容器
    //基于classnames缓存的全限定类名，以及反射技术，完成对象的创建和管理
    private void doInstance() {
        if (classNames.size() == 0) return;
        try {
            for (int i = 0; i < classNames.size(); i++) {
                String classname = classNames.get(i);

                //反射
                Class<?> aClass = null;

                aClass = Class.forName(classname);

                //区分controller，区分service
                if (aClass.isAnnotationPresent(MyController.class)){
                // controller的id此处不做过多处理，不取value了，就拿类的首字母小写作为id，保存到ioc中
                    String simpleName = aClass.getSimpleName();//DemoController
                    String lowerFirstSimpleName =  lowerFirst(simpleName);
                    Object o = aClass.newInstance();
                    ioc.put(lowerFirstSimpleName, o);
                }else if(aClass.isAnnotationPresent(MyService.class)){
                    MyService annotation = aClass.getAnnotation(MyService.class);
                    //获取注解value值
                    String beanName = annotation.value();

                    //如果指定了name，以指定的为准
                    if (!"".equals(beanName.trim())){
                        ioc.put(beanName, aClass.newInstance());
                    }else {
                        //如果没有指定，以类的小写字母为名字
                        beanName = lowerFirst(aClass.getSimpleName());
                        ioc.put(beanName, aClass.newInstance());
                    }

                    //service层往往是有接口的，面向接口开发，此时再以接口名为id，放一份对象到ioc中，便于后期根据接口类型注入
                    Class<?>[] interfaces = aClass.getInterfaces();
                    for (int j = 0; j < interfaces.length; j++) {
                        Class<?> anInterface = interfaces[j];
                        //以接口的全限定类名作为id放入
                        ioc.put(anInterface.getName(), aClass.newInstance());
                    }
                }else {
                    continue;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String lowerFirst(String str) {
        char[] chars = str.toCharArray();
        if ('A' <= chars[0] && chars[0] <= 'Z'){
            chars[0]+=32;
        }
        return String.valueOf(chars);
    }

    // 扫描类
    // scanPackage: com.lagou.demo  package---->  磁盘上的文件夹（File）  com/lagou/demo
    private void doScan(String scanPackage) {
        String scanPackagePath = Thread.currentThread().getContextClassLoader().getResource("").getPath() + scanPackage.replaceAll("\\.", "/");
        File pack = new File(scanPackagePath);

        File[] files = pack.listFiles();

        for(File file: files) {
            if(file.isDirectory()) { // 子package
                // 递归
                doScan(scanPackage + "." + file.getName());  // com.lagou.demo.controller
            }else if(file.getName().endsWith(".class")) {
                String className = scanPackage + "." + file.getName().replaceAll(".class", "");
                classNames.add(className);
            }
        }
    }

    private void doLoadConfig(String contextConfigLocation) {
        InputStream resourceAsStream = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);
        try {
            properties.load(resourceAsStream);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
       doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        //处理请求：根据url找到对应的method方法，进行调用
        //根据url获取能处理当前请求的handle（从handleMapping中）
        Handler handle = getHandle(req);
        if (handle == null) {
            resp.getWriter().write("404 NOT FOUND");
            return;
        }

        //参数绑定
        //获取所有参数类型数组，这个数组的长度就是传入args的长度
        Class<?>[] parameterTypes = handle.getMethod().getParameterTypes();

        //根据上述数组长度创建一个新的参数数组（是传入反射调用的）
        Object[] paraValues = new Object[parameterTypes.length];

        // 以下就是为了向参数数组中塞值，而且还得保证参数的顺序和方法中形参顺序一致
        Map<String, String[]> parameterMap = req.getParameterMap();

        // 遍历request中所有参数  （填充除了request，response之外的参数）
        for (Map.Entry<String, String[]> entry : parameterMap.entrySet()) {
            // name=1&name=2   name [1,2]
            String value = StringUtils.join(entry.getValue(), ",");

            //如果参数和方法中的参数匹配上了 填充数据
            if (!handle.getParamIndexMapping().containsKey(entry.getKey())){
                continue;
            }

            Integer index = handle.getParamIndexMapping().get(entry.getKey());
            paraValues[index] = value;
        }

        Integer requestIndex = handle.getParamIndexMapping().get(HttpServletRequest.class.getSimpleName());
        paraValues[requestIndex] = req;

        Integer responseIndex = handle.getParamIndexMapping().get(HttpServletResponse.class.getSimpleName());
        paraValues[responseIndex] = resp;

        // 最终调用handler的method属性
        try {
            handle.getMethod().invoke(handle.getController(), paraValues);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private Handler getHandle(HttpServletRequest req) {
        if (handlerMapping.isEmpty())return null;

        String requestURI = req.getRequestURI();
        for (Handler handler : handlerMapping) {
            Matcher matcher = handler.getPattern().matcher(requestURI);
            if (!matcher.matches()) continue;
            return handler;
        }
        return null;
    }
}
