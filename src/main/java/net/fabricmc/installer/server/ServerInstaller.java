/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
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

package net.fabricmc.installer.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import mjson.Json;

import net.fabricmc.installer.LoaderVersion;
import net.fabricmc.installer.util.InstallerProgress;
import net.fabricmc.installer.util.Library;
import net.fabricmc.installer.util.Utils;

public class ServerInstaller {
	private static final String servicesDir = "META-INF/services/";
	private static final String manifestPath = "META-INF/MANIFEST.MF";
	public static final String DEFAULT_LAUNCH_JAR_NAME = "fabric-server-launch.jar";
	private static final Pattern SIGNATURE_FILE_PATTERN = Pattern.compile("META-INF/[^/]+\\.(SF|DSA|RSA|EC)");

	public static void install(Path dir, LoaderVersion loaderVersion, String gameVersion, InstallerProgress progress) throws IOException {
		Path launchJar = dir.resolve(DEFAULT_LAUNCH_JAR_NAME);
		install(dir, loaderVersion, gameVersion, progress, launchJar);
	}

	private static boolean isOldGuava(String mcVersion) {
		return !(-1 < Utils.compareVersions("1.8.9", mcVersion));
	}

	public static void install(Path dir, LoaderVersion loaderVersion, String gameVersion, InstallerProgress progress, Path launchJar) throws IOException {
		boolean legacyLoader = loaderVersion.name.length() > 10;

		if (Objects.equals(gameVersion, "1.8.9") && legacyLoader) throw new IOException("1.8.9 server is incompatible with version 0.11.x and older, please use 0.12 and newer!");

		progress.updateProgress(new MessageFormat(Utils.BUNDLE.getString("progress.installing.server")).format(new Object[]{String.format("%s(%s)", loaderVersion.name, gameVersion)}));

		Files.createDirectories(dir);

		Path libsDir = dir.resolve("libraries");
		Files.createDirectories(libsDir);

		progress.updateProgress(Utils.BUNDLE.getString("progress.download.libraries"));

		List<Library> libraries = new ArrayList<>();
		String mainClassMeta;

		if (loaderVersion.path == null) { // loader jar unavailable, grab everything from meta
			URL downloadUrl;

			if (legacyLoader) {
				downloadUrl = new URL(String.format("https://maven.legacyfabric.net/net/fabricmc/fabric-loader-1.8.9/%s/fabric-loader-1.8.9-%s.json", loaderVersion.name, loaderVersion.name));
			} else {
				downloadUrl = new URL(String.format("https://maven.fabricmc.net/net/fabricmc/fabric-loader/%s/fabric-loader-%s.json", loaderVersion.name, loaderVersion.name));
			}

			Json json = Json.read(Utils.readTextFile(downloadUrl));

			libraries.add(new Library(String.format(
					legacyLoader ? "net.fabricmc:fabric-loader-1.8.9:%s" : "net.fabricmc:fabric-loader:%s", loaderVersion.name),
					legacyLoader ? "https://maven.legacyfabric.net/" : "https://maven.fabricmc.net/", null));
			libraries.add(new Library(String.format("net.fabricmc:intermediary:%s", gameVersion), "https://maven.legacyfabric.net/", null));

			for (Json libraryJson : json.at("libraries").at("common").asJsonList()) {
				libraries.add(new Library(libraryJson));
			}

			if (!isOldGuava(gameVersion)) {
				for (Json libraryJson : json.at("libraries").at("server").asJsonList()) {
					libraries.add(new Library(libraryJson));
				}
			}

			if (isOldGuava(gameVersion) && legacyLoader) {
				libraries.add(new Library(
						"dev.blucobalt:mcguava:0.07",
						"https://repo.blucobalt.dev/repository/maven-hosted/",
						null
				));
			}

			if (isOldGuava(gameVersion)) {
				libraries.add(new Library("org.apache.logging.log4j:log4j-api:2.8.1", "https://libraries.minecraft.net/", null));
				libraries.add(new Library("org.apache.logging.log4j:log4j-core:2.8.1", "https://libraries.minecraft.net/", null));
			}

			mainClassMeta = json.at("mainClass").at("server").asString();
		} else { // loader jar available, generate library list from it
			libraries.add(new Library(String.format("net.fabricmc:fabric-loader:%s", loaderVersion.name), null, loaderVersion.path));
			libraries.add(new Library(String.format("net.fabricmc:intermediary:%s", gameVersion), "https://maven.legacyfabric.net/", null));

			try (ZipFile zf = new ZipFile(loaderVersion.path.toFile())) {
				ZipEntry entry = zf.getEntry("fabric-installer.json");
				Json json = Json.read(Utils.readString(zf.getInputStream(entry)));
				Json librariesElem = json.at("libraries");

				for (Json libraryJson : librariesElem.at("common").asJsonList()) {
					libraries.add(new Library(libraryJson));
				}

				for (Json libraryJson : librariesElem.at("server").asJsonList()) {
					libraries.add(new Library(libraryJson));
				}

				mainClassMeta = json.at("mainClass").at("server").asString();
			}
		}

		String mainClassManifest = "net.fabricmc.loader.launch.server.FabricServerLauncher";
		List<Path> libraryFiles = new ArrayList<>();

		for (Library library : libraries) {
			Path libraryFile = libsDir.resolve(library.getFileName());

			if (library.inputPath == null) {
				progress.updateProgress(new MessageFormat(Utils.BUNDLE.getString("progress.download.library.entry")).format(new Object[]{library.name}));
				Utils.downloadFile(new URL(library.getURL()), libraryFile);
			} else {
				Files.createDirectories(libraryFile.getParent());
				Files.copy(library.inputPath, libraryFile, StandardCopyOption.REPLACE_EXISTING);
			}

			libraryFiles.add(libraryFile);

			if (library.name.matches("net\\.fabricmc:fabric-loader:.*")) {
				try (JarFile jarFile = new JarFile(libraryFile.toFile())) {
					Manifest manifest = jarFile.getManifest();
					mainClassManifest = manifest.getMainAttributes().getValue("Main-Class");
				}
			}
		}

		progress.updateProgress(Utils.BUNDLE.getString("progress.generating.launch.jar"));

		boolean shadeLibraries = Utils.compareVersions(loaderVersion.name, "0.12.5") <= 0; // FabricServerLauncher in Fabric Loader 0.12.5 and earlier requires shading the libs into the launch jar
		makeLaunchJar(launchJar, mainClassMeta, mainClassManifest, libraryFiles, shadeLibraries, progress);
	}

	private static void makeLaunchJar(Path file, String launchMainClass, String jarMainClass, List<Path> libraryFiles,
			boolean shadeLibraries, InstallerProgress progress) throws IOException {
		Files.deleteIfExists(file);

		try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(file))) {
			Set<String> addedEntries = new HashSet<>();

			addedEntries.add(manifestPath);
			zipOutputStream.putNextEntry(new ZipEntry(manifestPath));

			Manifest manifest = new Manifest();
			Attributes mainAttributes = manifest.getMainAttributes();

			mainAttributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
			mainAttributes.put(Attributes.Name.MAIN_CLASS, jarMainClass);

			if (!shadeLibraries) {
				mainAttributes.put(Attributes.Name.CLASS_PATH, libraryFiles.stream()
						.map(f -> file.getParent().relativize(f).normalize().toString())
						.collect(Collectors.joining(" ")));
			}

			manifest.write(zipOutputStream);

			zipOutputStream.closeEntry();

			addedEntries.add("fabric-server-launch.properties");
			zipOutputStream.putNextEntry(new ZipEntry("fabric-server-launch.properties"));
			zipOutputStream.write(("launch.mainClass=" + launchMainClass + "\n").getBytes(StandardCharsets.UTF_8));
			zipOutputStream.closeEntry();

			if (shadeLibraries) {
				Map<String, Set<String>> services = new HashMap<>();
				byte[] buffer = new byte[32768];

				for (Path f : libraryFiles) {
					progress.updateProgress(new MessageFormat(Utils.BUNDLE.getString("progress.generating.launch.jar.library")).format(new Object[]{f.getFileName().toString()}));

					// read service definitions (merging them), copy other files
					try (JarInputStream jis = new JarInputStream(Files.newInputStream(f))) {
						JarEntry entry;

						while ((entry = jis.getNextJarEntry()) != null) {
							if (entry.isDirectory()) continue;

							String name = entry.getName();

							if (name.startsWith(servicesDir) && name.indexOf('/', servicesDir.length()) < 0) { // service definition file
								parseServiceDefinition(name, jis, services);
							} else if (SIGNATURE_FILE_PATTERN.matcher(name).matches()) {
								// signature file, ignore
							} else if (!addedEntries.add(name)) {
								System.out.printf("duplicate file: %s%n", name);
							} else {
								JarEntry newEntry = new JarEntry(name);
								zipOutputStream.putNextEntry(newEntry);

								int r;

								while ((r = jis.read(buffer, 0, buffer.length)) >= 0) {
									zipOutputStream.write(buffer, 0, r);
								}

								zipOutputStream.closeEntry();
							}
						}
					}
				}

				// write service definitions
				for (Map.Entry<String, Set<String>> entry : services.entrySet()) {
					JarEntry newEntry = new JarEntry(entry.getKey());
					zipOutputStream.putNextEntry(newEntry);

					writeServiceDefinition(entry.getValue(), zipOutputStream);

					zipOutputStream.closeEntry();
				}
			}
		}
	}

	private static void parseServiceDefinition(String name, InputStream rawIs, Map<String, Set<String>> services) throws IOException {
		Collection<String> out = null;
		BufferedReader reader = new BufferedReader(new InputStreamReader(rawIs, StandardCharsets.UTF_8));
		String line;

		while ((line = reader.readLine()) != null) {
			int pos = line.indexOf('#');
			if (pos >= 0) line = line.substring(0, pos);
			line = line.trim();

			if (!line.isEmpty()) {
				if (out == null) out = services.computeIfAbsent(name, ignore -> new LinkedHashSet<>());

				out.add(line);
			}
		}
	}

	private static void writeServiceDefinition(Collection<String> defs, OutputStream os) throws IOException {
		BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8));

		for (String def : defs) {
			writer.write(def);
			writer.write('\n');
		}

		writer.flush();
	}
}
