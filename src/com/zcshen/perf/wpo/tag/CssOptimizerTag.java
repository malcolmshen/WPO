package com.zcshen.perf.wpo.tag;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Set;

import javax.servlet.jsp.JspException;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yahoo.platform.yui.compressor.CssCompressor;

public class CssOptimizerTag extends AbstractOptimizerTag {

	private static final long serialVersionUID = 235713574851447507L;
	private static final Logger log = LoggerFactory.getLogger(CssOptimizerTag.class);
	private static final String TEXT_CSS = "text/css";
	
	@Override
	public int doStartTag() throws JspException {
		return super.doStartTag();
	}
	
	@Override
	protected OptTag getStandardOptTag() {
		OptTag tag = new OptTag();
		tag.setTagName("link");
		tag.setSrcAttributeName("href");
		tag.attr("type", TEXT_CSS);
		tag.attr("rel", "stylesheet");
		return tag;
	}
	
	@Override
	protected boolean needOptimize(OptTag tag) {
		String src = tag.attr("href");
		String noOpt = tag.attr("noOpt");
		String scriptType = StringUtils.trimToEmpty(tag.attr("type"));

		return TEXT_CSS.equalsIgnoreCase(scriptType) && StringUtils.isNotBlank(src) && !Boolean.parseBoolean(noOpt);
	}
	
	@Override
	protected String getMergedFileRelativePath(Set<String> resourcePaths) {
		return "/style/" + getMergedFilenamePrefix(resourcePaths) + ".css";
	}
	
	@Override
	protected void minify(String resourcePath) {
		long startTime = System.currentTimeMillis();
		String realPath = getRealPath(resourcePath);
		Reader in = null;
		Writer out = null;
		try {
			in = new InputStreamReader(new FileInputStream(realPath), getCharset());
			CssCompressor compressor = new CssCompressor(in);
			
            // Close the input stream first, and then open the output stream,
            // in case the output file should override the input file.
			IOUtils.closeQuietly(in); in = null;

			out = new OutputStreamWriter(new FileOutputStream(realPath), getCharset());
			compressor.compress(out, 0);
		} catch (Exception e) {
			log.error("Fail to compress JS", e);
		} finally {
			IOUtils.closeQuietly(in);
			IOUtils.closeQuietly(out);
			if (log.isDebugEnabled()) {
				log.debug("Minifying " + realPath + " takes: " + (System.currentTimeMillis() - startTime));
			}
		}
	}
	
	@Override
	protected void compress(String resourcePath) {
		// no compress for css since we have gzip at server side.
	}
}
