package com.zyblue.fastim.fastim.gate.tcp.service.impl;

import com.zyblue.fastim.common.ServerInfo;
import com.zyblue.fastim.common.pojo.request.LoginRequest;
import com.zyblue.fastim.common.pojo.response.LoginResponse;
import com.zyblue.fastim.common.redis.RedisKey;
import com.zyblue.fastim.fastim.gate.tcp.service.ServerInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class ServerInfoServiceImpl implements ServerInfoService {

    public static final Integer EXPIRED_TIME = 30;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public LoginResponse getServerInfo(LoginRequest request) {
        ServerInfo info = new ServerInfo();
        info.setIp("127.0.0.1");
        info.setServerPort(9527);

        LoginResponse loginResponse = new LoginResponse();
        loginResponse.setToken("zyblue");
        loginResponse.setServerInfo(info);
        stringRedisTemplate.opsForValue().set(RedisKey.FASTIM_TOKEN + 9527, "zyblue", EXPIRED_TIME, TimeUnit.MINUTES);
        return loginResponse;
    }
}