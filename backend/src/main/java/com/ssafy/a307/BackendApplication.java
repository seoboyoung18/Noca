package com.ssafy.a307;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * {@code @ConfigurationPropertiesScan} 은 {@code com.ssafy.a307} 아래의
 * {@code @ConfigurationProperties} 클래스를 자동으로 등록한다.
 * 클래스마다 {@code @EnableConfigurationProperties} 를 어딘가에 적어 주는 방식 대신 이걸 고른 이유는,
 * 등록을 빠뜨렸을 때 "설정이 조용히 안 먹는" 실패가 나기 때문이다.
 * 스캔은 한 번만 켜 두면 뒤에 오는 설정 클래스는 애너테이션만 붙이면 된다.
 */
@ConfigurationPropertiesScan
@SpringBootApplication
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
