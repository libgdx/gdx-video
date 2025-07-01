/*******************************************************************************
 * Copyright 2025 See AUTHORS file.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.video.test.teavm;

import com.github.xpenatan.gdx.backends.teavm.config.AssetFileHandle;
import com.github.xpenatan.gdx.backends.teavm.config.TeaBuildConfiguration;
import com.github.xpenatan.gdx.backends.teavm.config.TeaBuilder;
import java.io.File;
import java.io.IOException;
import org.teavm.tooling.TeaVMTool;
import org.teavm.vm.TeaVMOptimizationLevel;

/** Builds the TeaVM/HTML application. */
public class TeaVMBuilder {
	public static void main (String[] args) throws IOException {
		TeaBuildConfiguration teaBuildConfiguration = new TeaBuildConfiguration();
		teaBuildConfiguration.assetsPath.add(new AssetFileHandle("../assets"));
		teaBuildConfiguration.webappPath = new File("build/dist").getCanonicalPath();

		// Register any extra classpath assets here:
		// teaBuildConfiguration.additionalAssetsClasspathFiles.add("com/badlogic/gdx/video/test/asset.extension");

		// Register any classes or packages that require reflection here:
		// TeaReflectionSupplier.addReflectionClass("com.badlogic.gdx.video.test.reflect");

		TeaVMTool tool = TeaBuilder.config(teaBuildConfiguration);
		tool.setMainClass(TeaVMLauncher.class.getName());
		// For many (or most) applications, using a high optimization won't add much to build time.
		// If your builds take too long, and runtime performance doesn't matter, you can change ADVANCED to SIMPLE .
		tool.setOptimizationLevel(TeaVMOptimizationLevel.ADVANCED);
		tool.setObfuscated(true);
		TeaBuilder.build(tool);
	}
}
