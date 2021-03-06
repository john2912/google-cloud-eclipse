/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.tools.eclipse.appengine.facets;

import com.google.cloud.tools.eclipse.test.util.BasePluginXmlTest;
import org.eclipse.wst.common.project.facet.core.ProjectFacetsManager;
import org.junit.Assert;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class PluginXmlTest extends BasePluginXmlTest {
  
  @Test
  public void testFacetsDefined() {
    checkFacetDefined(getDocument(), "conflicts");
    checkFacetDefined(getDocument(), "requires");
  }

  private static void checkFacetDefined(Document doc, String elementName) {
    NodeList elements = doc.getElementsByTagName(elementName);
    for (int i = 0; i < elements.getLength(); i++) {
      Element element = (Element) elements.item(i);
      String facet = element.getAttribute("facet");
      Assert.assertTrue(facet.isEmpty() || ProjectFacetsManager.isProjectFacetDefined(facet));
      String group = element.getAttribute("group");
      Assert.assertTrue(group.isEmpty() || ProjectFacetsManager.isGroupDefined(group));
    }
  }
  
  @Test
  public void testExpressionDefinitions() {
    NodeList extensions = getExtensions("org.eclipse.core.expressions.definitions");
    Assert.assertEquals(3, extensions.getLength());
    // each extension has a single definition
    String[] expectedDefinitionIds = {
      "com.google.cloud.tools.eclipse.appengine.isStandardProject",
      "com.google.cloud.tools.eclipse.appengine.isFlexWarProject",
      "com.google.cloud.tools.eclipse.appengine.isFlexJarMavenProject"
    };
    for(int i = 0; i < extensions.getLength(); i++) {
      Assert.assertTrue(extensions.item(i) instanceof Element);
      Element element = (Element) extensions.item(i);
      NodeList definitions = element.getElementsByTagName("definition");
      // each definition has 1 element
      Assert.assertEquals(1, definitions.getLength());
      Assert.assertTrue(definitions.item(0) instanceof Element);
      Element definition = (Element) definitions.item(0);
      Assert.assertEquals(expectedDefinitionIds[i], definition.getAttribute("id"));
      checkExpressionDefinition(definition);
    }
  }
}
