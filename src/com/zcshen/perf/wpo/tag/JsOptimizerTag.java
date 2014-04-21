package com.zcshen.perf.wpo.tag;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.Set;

import javax.servlet.jsp.JspException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JsOptimizerTag extends AbstractOptimizerTag {

	private static final long serialVersionUID = 149873574851447507L;
	private static final Logger log = LoggerFactory.getLogger(JsOptimizerTag.class);
	private static final String TEXT_JAVASCRIPT = "text/javascript";
	private static final String APP_JAVASCRIPT = "application/javascript";
	
	@Override
	public int doStartTag() throws JspException {
		return super.doStartTag();
	}
	
	@Override
	protected OptTag getStandardOptTag() {
		OptTag tag = new OptTag();
		tag.setTagName("script");
		tag.setSrcAttributeName("src");
		tag.attr("type", TEXT_JAVASCRIPT);
		return tag;
	}
	
	@Override
	protected boolean needOptimize(OptTag tag) {
		String src = tag.attr("src");
		String noOpt = tag.attr("noOpt");
		String scriptType = StringUtils.trimToEmpty(tag.attr("type"));

		return (TEXT_JAVASCRIPT.equalsIgnoreCase(scriptType) || APP_JAVASCRIPT.equalsIgnoreCase(scriptType)) 
				&& StringUtils.isNotBlank(src) 
				&& !Boolean.parseBoolean(noOpt);
	}
	
	@Override
	protected String getMergedFileRelativePath(Set<String> resourcePaths) {
		return "/js/" + getMergedFilenamePrefix(resourcePaths) + ".js";
	}
	
	@Override
	protected void minify(String resourcePath) {
		long startTime = System.currentTimeMillis();
		String realPath = getRealPath(resourcePath);
		Reader in = null;
		try {
			File file = new File(realPath);
			in = new BufferedReader(new InputStreamReader(FileUtils.openInputStream(file), getCharset()));
			JSFastWhitespaceRemover minifier = new JSFastWhitespaceRemover();
			StringWriter writer = new StringWriter();
			minifier.compress(in, writer);
			
            // Close the input stream first, and then open the output stream,
            // in case the output file should override the input file.
			IOUtils.closeQuietly(in); in = null;
			
			FileUtils.writeStringToFile(file, writer.toString(), getCharset());
		} catch (Exception e) {
			log.error("Fail to compress JS", e);
		} finally {
			IOUtils.closeQuietly(in);
			if (log.isDebugEnabled()) {
				log.debug("Minifying " + realPath + " takes: " + (System.currentTimeMillis() - startTime));
			}
		}
	}
	
	@Override
	protected void compress(String jsName) {
		// no compress for js since we have gzip at server side.
	}
	
	/**
	 * This class is extracted from http://code.google.com/p/granule/ project.  
	 * Thanks for that. 
	 * 
	 * Simple and Fast JS Compresser. It removes whitespace and comments from source code in safest way is possible.
	 * Ignores IE conditional comments, 
	 *
	 * * @author Jonathan Walsh 
	 */
	class JSFastWhitespaceRemover {
		
		public void compress(final Reader in, final Writer out) throws IOException {
			ParseState state = ParseState.TEXT_OK_SKIP_SPACE;
			ParseState stateText = ParseState.TEXT_OK_SKIP_SPACE;
			int c;
			char prevLex = ' ';
			int commentLength = 0;
			int quote = -1;
			while ((c = in.read()) != -1) {

				if (c == '\r' || c == '\n')
					c = '\n';
				else if (c < ' ') {
					continue;
				}

				switch (state) {
				case QUOTE:
					out.write(c);
					if (quote == c) {
						state = ParseState.TEXT_OK_SKIP_SPACE;
					}
					break;

				case TEXT_OK_SKIP_SPACE:
					if (c == ' '|| c=='\n') {
						break;
					} 

				case TEXT_BREAK:
					if (c == '\n') {
						break;
					} 

				case TEXT:
					if (c == '/') {
						state = ParseState.SLASH;
						if (prevLex == '(' || prevLex == ',' || prevLex == '=' || prevLex == ':' || prevLex == '['
								|| prevLex == '!' || prevLex == '&' || prevLex == '|' || prevLex == '?' || prevLex == '{'
								|| prevLex == '}' || prevLex == ';' || prevLex == '\n')
							state = ParseState.MAY_REGULAR_EXPR;
						break;
					} else if (c == ' ' || c == '{' || c == ',' || c == ';' || c == ':' || c=='=' || 
							   c == '(' || c == '[' || c == '!' || c == '&' || c == '|' || c=='?'
							   ) 
						state = ParseState.TEXT_OK_SKIP_SPACE;
					else if (c == '\n')
						state = ParseState.TEXT_BREAK;
					else if (c == '\'' || c == '"') {
						state = ParseState.QUOTE;
						quote = c;
					} else
						state = ParseState.TEXT;
					out.write(c);
					stateText=state;
					break;

				case STAR_IN_COMMENT:
					if (c == '/')
						state = stateText;
					else if (c == '*')
						state = ParseState.STAR_IN_COMMENT;
					else
						state = ParseState.STARTED_COMMENT;
					break;

				case MAY_REGULAR_EXPR:
					if (c == '*') {
						state = ParseState.STARTED_COMMENT;
						commentLength = 0;
					} else if (c == '/') {
						state = ParseState.LINE_COMMENT;
					} else if (c == '\n') {
						state = ParseState.TEXT;
						out.write(c);
					} else {
						state = ParseState.REGULAR_EXPR;
						out.write('/');
						out.write(c);
					}
					break;

				case REGULAR_EXPR:
					if (c == '\n') {
						state = ParseState.TEXT;
					}
					out.write(c);
					break;

				case SLASH:
					if (c == '*') {
						state = ParseState.STARTED_COMMENT;
						commentLength = 0;
						break;
					} else if (c == '/') {
						state = ParseState.LINE_COMMENT;
						break;
					} else {
						out.write('/');
						out.write(c);
						state = ParseState.TEXT;
					}
					break;

				case STARTED_COMMENT:
					if (c == '*')
						state = ParseState.STAR_IN_COMMENT;
					else if (commentLength == 0 && c == '@') {
						out.write('/');
						out.write('*');
						out.write(c);
						state = ParseState.CONDITIONAL_COMMENT;
					}
					commentLength++;
					break;

				case CONDITIONAL_COMMENT:
					if (c == '*')
						state = ParseState.CLOSING_STAR_IN_COND_COMMENT;
					else
						state = ParseState.CONDITIONAL_COMMENT;
					out.write(c);
					break;

				case CLOSING_STAR_IN_COND_COMMENT:
					if (c == '/')
						state = stateText;
					else if (c == '*')
						state = ParseState.CLOSING_STAR_IN_COND_COMMENT;
					else
						state = ParseState.CONDITIONAL_COMMENT;
					out.write(c);
					break;

				case LINE_COMMENT:
					if (c == '\n')
						state= ParseState.TEXT_OK_SKIP_SPACE;
					break;

				}
				if (c != ' ')
					prevLex = (char) c;
			}
		}
	}

	//States
	enum ParseState {
		TEXT,
		TEXT_OK_SKIP_SPACE,
		SLASH,
		STARTED_COMMENT,
		TAR_IN_COMMENT,
		EXT_OK_SKIP_SPACE,
		LINE_COMMENT,
		TEXT_BREAK,
		STAR_IN_COMMENT,
		CONDITIONAL_COMMENT,
		CLOSING_STAR_IN_COND_COMMENT,
		QUOTE,
		MAY_REGULAR_EXPR,
		REGULAR_EXPR
	}

}
