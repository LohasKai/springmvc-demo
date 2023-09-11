package com.mhz.demo.controller;

import com.mhz.demo.service.IDemoService;
import com.mhz.mvcframework.annotations.MyAutowired;
import com.mhz.mvcframework.annotations.MyController;
import com.mhz.mvcframework.annotations.MyRequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@MyController
@MyRequestMapping("/demo")
public class DemoController {

    @MyAutowired
    private IDemoService iDemoService;

    @MyRequestMapping("/test")
    public String query(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, String name){
        return iDemoService.getSuccess(name);
    }
}
