package com.zinnia.regression;

import com.zinnia.regression.cli.RegressionCli;
import com.zinnia.regression.config.DotenvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class RegressionApplication {

    public static void main(String[] args) {
        if (RegressionCli.handles(args)) {
            System.exit(RegressionCli.run(args, System.out, System.err));
        }
        SpringApplication app = new SpringApplication(RegressionApplication.class);
        app.setDefaultProperties(DotenvLoader.load());
        app.run(args);
    }
}
