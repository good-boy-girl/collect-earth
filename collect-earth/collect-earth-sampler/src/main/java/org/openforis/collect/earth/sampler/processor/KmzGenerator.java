package org.openforis.collect.earth.sampler.processor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class KmzGenerator {

	private final Logger logger = LoggerFactory.getLogger(KmzGenerator.class);

	private void addFileToZip(String path, String srcFile, ZipOutputStream zip) throws IOException {

		final File file = new File(srcFile);
		if (file.isDirectory()) {
			addFolderToZip(path, srcFile, zip);
		} else {
			final byte[] buf = new byte[1024];
			int len;

			try (
					FileInputStream in = new FileInputStream(srcFile); 
					){

				String filePathName = path + "/" + file.getName();
				// if in root folder no / necessary
				if (path.length() == 0) {
					filePathName = file.getName();
				}
				zip.putNextEntry(new ZipEntry(filePathName));
				while ((len = in.read(buf)) > 0) {
					zip.write(buf, 0, len);
				}

			} catch (final IOException e) {
				logger.error("Error while writing to " + srcFile, e);
			} finally {
				zip.closeEntry();
			}

		}
	}

	private void addFolderToZip(String path, String srcFolder, ZipOutputStream zip) throws IOException {
		final File folder = new File(srcFolder);

		for (final String fileName : folder.list()) {
			if (path.equals("")) {
				addFileToZip(folder.getName(), srcFolder + "/" + fileName, zip);
			} else {
				addFileToZip(path + "/" + folder.getName(), srcFolder + "/" + fileName, zip);
			}
		}
	}

	public void generateKmzFile(String kmzFilename, String kmlFile, String dependantFolder) throws IOException {


		try (
				FileOutputStream fileWriter = new FileOutputStream(kmzFilename);
				ZipOutputStream zip = new ZipOutputStream(fileWriter);

		){
			// Add the KML to the root folder
			addFileToZip("", kmlFile, zip);

			// Add the Images/JS etc to the file folder
			if (dependantFolder != null) {
				addFolderToZip("", dependantFolder, zip);
			}
		} catch (final FileNotFoundException e) {
			logger.error("Could not find file " + e.getMessage(), e);
		} catch (final IOException e) {
			logger.error(e.getMessage(), e);
		} catch (final Exception e) {
			logger.error(e.getMessage(), e);
		}
	}
}