package org.openforis.collect.earth.sampler.utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.Version;


public class FreemarkerTemplateUtils {

	private static final Logger logger = LoggerFactory.getLogger(FreemarkerTemplateUtils.class);

	private static final Random rand = new Random();
	
	private FreemarkerTemplateUtils() {}

	public static boolean applyTemplate(File sourceTemplate, File destinationFile, Map<?, ?> data) throws IOException, TemplateException{
		boolean success = false;

		// Console output
		try ( BufferedWriter fw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(destinationFile), Charset.forName("UTF-8"))) ) {

			// Process the template file using the data in the "data" Map
			final Configuration cfg = new Configuration( new Version("2.3.23"));
			cfg.setDirectoryForTemplateLoading(sourceTemplate.getParentFile());

			// Load template from source folder
			final Template template = cfg.getTemplate(sourceTemplate.getName());

			template.process(data, fw);
			success = true;
		}catch (Exception e) {
			logger.error("Error reading FreeMarker template", e);
		} 
		return success;

	}

	/**
	 * Returns a pseudo-random number between min and max, inclusive.
	 * The difference between min and max can be at most
	 * <code>Integer.MAX_VALUE - 1</code>.
	 *
	 * @param min Minimum value
	 * @param max Maximum value.  Must be greater than min.
	 * @return Integer between min and max, inclusive.
	 * @see java.util.Random#nextInt(int)
	 */
	public static int randInt(int min, int max) {
		// nextInt is normally exclusive of the top value,
		// so add 1 to make it inclusive
		return rand.nextInt((max - min) + 1) + min;
	}



}
