package personal.currency.point;

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

/**
 * Cucumber 테스트 실행을 위한 JUnit Platform Suite Runner
 * - HTML 리포트, JSON 리포트, Pretty 콘솔 출력 설정
 * - 테스트 결과는 build/cucumber-reports 디렉토리에 저장됨
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME, value = "personal.currency.point")
@ConfigurationParameter(key = Constants.PLUGIN_PROPERTY_NAME, value = "pretty, html:build/cucumber-reports/cucumber.html, json:build/cucumber-reports/cucumber.json")
@ConfigurationParameter(key = Constants.FILTER_TAGS_PROPERTY_NAME, value = "not @Disabled")
public class CucumberTestRunner {
}
