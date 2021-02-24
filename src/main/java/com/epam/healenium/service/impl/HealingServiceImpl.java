/**
 * Healenium-web Copyright (C) 2019 EPAM
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *        http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.healenium.service.impl;

import com.epam.healenium.PageAwareBy;
import com.epam.healenium.SelfHealingEngine;
import com.epam.healenium.client.RestClient;
import com.epam.healenium.service.HealingService;
import com.epam.healenium.treecomparing.Scored;
import com.epam.healenium.utils.StackUtils;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.*;
import org.openqa.selenium.remote.Augmenter;

import java.util.List;
import java.util.Optional;

@Slf4j
public class HealingServiceImpl implements HealingService {

    private final SelfHealingEngine engine;
    private final WebDriver driver;

    public HealingServiceImpl(SelfHealingEngine engine) {
        this.engine = engine;
        this.driver = engine.getWebDriver();
    }

    public Optional<WebElement> heal(PageAwareBy pageBy, NoSuchElementException ex) {
        // check if already have healed results
//        RequestDto request = mapper.buildDto(pageBy.getBy(), e.getStackTrace(), pageSource());
        if (engine.getCurrentShadowRoot()!=null){
            return healLocator(pageBy, ex.getStackTrace()).map(engine.getCurrentShadowRoot()::findElement);
        } else {
            return healLocator(pageBy, ex.getStackTrace()).map(driver::findElement);
        }
    }

    /**
     *
     * @param pageBy
     * @param trace
     * @return
     */
    private Optional<By> healLocator(PageAwareBy pageBy, StackTraceElement[] trace) {
        // collect page content
        String pageContent = pageSource(pageBy);
        // search target point in stacktrace
        Optional<StackTraceElement> traceElement = StackUtils.findOriginCaller(trace);
        // search for possible healing results
        List<Scored<By>> choices = engine.findNewLocations(pageBy, pageSource(pageBy), traceElement);
        Optional<Scored<By>> result = choices.stream().findFirst();
        if (!result.isPresent()) {
            log.warn("New element locators have not been found");
        } else {
            Scored<By> healed = result.get();
            log.warn("Using healed locator: {}", result.toString());
            byte[] screenshot = captureScreen(healed);
            traceElement.ifPresent(it-> {
                // build request and send it to server
                engine.getClient().healRequest(pageBy.getBy(), it, pageContent, choices, healed, screenshot);
            });
        }
        return result.map(Scored::getValue);
    }




    /**
     * Create screenshot of healed element
     * @param byScored - healed locator
     * @return path to screenshot location
     */
    //TODO: need pass search context here
    private byte[] captureScreen(Scored<By> byScored) {
        WebElement element;
        if (engine.getCurrentShadowRoot()!=null) {
            element = engine.getCurrentShadowRoot().findElement(byScored.getValue());
        } else {
            element = driver.findElement(byScored.getValue());
        }
        JavascriptExecutor jse = (JavascriptExecutor) driver;
        jse.executeScript("arguments[0].style.border='3px solid red'", element);
        WebDriver augmentedDriver = new Augmenter().augment(driver);
        return ((TakesScreenshot) augmentedDriver).getScreenshotAs(OutputType.BYTES);
    }

    private String pageSource() {
        if (driver instanceof JavascriptExecutor) {
            return ((JavascriptExecutor) driver).executeScript("return document.body.outerHTML;").toString();
        } else {
            return driver.getPageSource();
        }
    }

    private String pageSource(PageAwareBy pageBy) {
        if (pageBy.getDocumentShadowRoot()!=null){
            StringBuilder innerHTML = new StringBuilder();
            //bublegum to emulate regular DOM
            innerHTML.append("<html>")
                    .append(
                            ((JavascriptExecutor) driver).executeScript("return (arguments[0].innerHTML);",pageBy.getDocumentShadowRoot()).toString()
                    )
                    .append("</html>");
            return innerHTML.toString();
        } else {
            return pageSource();
        }

    }

}
