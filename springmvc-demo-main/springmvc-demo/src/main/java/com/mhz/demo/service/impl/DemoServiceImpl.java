package com.mhz.demo.service.impl;

import com.mhz.demo.service.IDemoService;
import com.mhz.mvcframework.annotations.MyService;

import java.util.Date;

@MyService
public class DemoServiceImpl implements IDemoService {

    @Override
    public String getSuccess(String name) {
        System.out.println("service 实现类中的name参数：" + name) ;
        return new Date().toString();
    }
}
