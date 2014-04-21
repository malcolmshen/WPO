package com.zcshen.perf.wpo.tag;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.commons.lang.StringUtils.trimToEmpty;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPOutputStream;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.jsp.JspException;
import javax.servlet.jsp.tagext.BodyTagSupport;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * If server restarts, the merged resource files would be regenerated.  
 * However, if number of referenced files or file name changes, the merged file name would be changed as well.
 * E.g., in a.jsp, it includes b.js.  If you changed the content of b.js and restart server, the merged file name would not be changed.
 * If you include one more js, c.js, the merged file name would be changed.
 * TODO considering using other strategy to be aware of file content change. 
 * 
 * Abstract optimizer tag, known implementations: 
 * {@link  com.unionpay.upop.web.tag.JsOptimizerTag},
 * {@link com.unionpay.upop.web.tag.CssOptimizerTag}
 * 
 * <ul>
 * Tag properties:
 * <li>enabled: decides whether the whole optimization is enabled. If set to false, no optimization would happen.
 * <li>exclude: keyword to exclude from optimization. Use comma to separate multiple keywords.
 * <li>charset: charset of the resource file to optimize. Default is UTF-8.
 * <li>minify: whether to minify the resources. Minify usually means removing spaces, comments or other useless information.
 * <li>compress: whether to compress the resources. Default is GZip.
 * <li>cache: whether to cache optimized html code for performance concern. Default is true.
 * <li>useServlet: whether to use servlet to return optimized resources other than static files.
 * </ul>
 * 
 * @author Malcolm
 * 
 */
public abstract class AbstractOptimizerTag extends BodyTagSupport {

	private static final long serialVersionUID = 684773574839747507L;
	private static final Logger log = LoggerFactory.getLogger(AbstractOptimizerTag.class);
	public static final String STATIC_MODE_FILE_PREFIX = "Opt_static_";
	public static final String SERVLET_MODE_FILE_PREFIX = "Opt_servlet_"; // TODO not supported yet
	private static final String NEW_LINE = System.getProperty("line.separator");

	private static Map<Set<String>, String> mergedResourcePaths = new ConcurrentHashMap<Set<String>, String>();
	private static Map<String, String> cachedOptBody = new ConcurrentHashMap<String, String>();
	private static ServletContext context;

	private Set<String> excludeSet = new HashSet<String>();
	private boolean enabled = true;
	private String charset = "UTF-8"; // default charset is UTF-8
	private boolean minify = true;
	private boolean compress = false;
	private boolean cache = true;
	private boolean useServlet = false;

	// TODO consider case: absolute path in src

	@Override
	public int doStartTag() throws JspException {
		if (context == null) {
			context = pageContext.getServletContext();
		}
		return (enabled) ? EVAL_BODY_BUFFERED : EVAL_BODY_INCLUDE;
	}

	@Override
	public int doEndTag() throws JspException {
		if (enabled) {
			String outputBody = "";
			try {
				String servletPath = this.getClass().getSimpleName()
						+ ((HttpServletRequest) pageContext.getRequest()).getServletPath();
				
				if (cache) {
					// get optimized content from cache, 
					// all referenced resources are merged into one file.
					outputBody = cachedOptBody.get(servletPath);
					if (isNotBlank(outputBody)) {
						if (log.isDebugEnabled()) {
							log.debug("Optimized body loaded from cache for " + servletPath);
						}
					}
				}

				if (isBlank(outputBody)) {
					// do optimization
					outputBody = getTrimedBodyContent();
					if (isNotBlank(outputBody)) {
						synchronized (this.getClass()) {
							outputBody = optimize(outputBody);
						}

						if (cache) {
							// put to cache
							cachedOptBody.put(servletPath, outputBody);
						}
					}
				}
			} catch (Throwable t) {
				log.error("Cannot generate optimized body on page!", t);
			} finally {
				outputBody = isBlank(outputBody) ? getTrimedBodyContent() : outputBody;
			}
			
			try {
				pageContext.getOut().println(outputBody);
			} catch (IOException e) {
				log.error("Cannot print optimized source on page!", e);
			}
		}

		return EVAL_PAGE;
	}

	public String getTrimedBodyContent() {
		// returns original body content.
		// return (getBodyContent() == null) ? "" : trimToEmpty(getBodyContent().getString());
		String result = getBodyContent().getString();
		result = removeToggleChars(result);
		return result;
	}

	/**
	 * Removes the TOGGLE char.  
	 * The TOGGLE char is added after every Stripes layout tag from 1.5.7. 
	 * This would conflict with JSoup and cause JS compile errors.
	 * 
	 * @param result
	 * @return
	 */
    private String removeToggleChars(String result) {
        if (result == null) {
			return "";
		} else {
		    final char TOGGLE = 0;
		    final char SPACE = 0x20;
		    result = result.replace(TOGGLE, SPACE);
		}
        return result;
    }
	private String optimize(String body) {
		Document doc = Jsoup.parseBodyFragment(body);
		Element element = doc.body();
		OptTag stdOptTag = getStandardOptTag();

		ListIterator<Element> scripts = element.getElementsByTag(stdOptTag.getTagName()).listIterator();
		if (scripts != null) {
			Set<String> resourcePathToOptimize = new LinkedHashSet<String>();
			Element mergedElement = null;

			while (scripts.hasNext()) {
				Element script = scripts.next();
				OptTag optTag = adaptToOptTag(script);
				String src = script.attr(stdOptTag.getSrcAttributeName());
				if (needOptimize(optTag) && !isExcluded(src)) {
					resourcePathToOptimize.add(src);
					if (mergedElement == null) {
						mergedElement = createElement(stdOptTag.getTagName(), stdOptTag.getAttributes());
						script.before(mergedElement);
					}
					script.remove();
				}
			}

			if (mergedElement != null) {
				String mergedResourceSrc = getMergedResourcePath(resourcePathToOptimize);
				mergedElement.attr(stdOptTag.getSrcAttributeName(), mergedResourceSrc);
			}
		}

		return element.html();
	}

	private OptTag adaptToOptTag(Element element) {
		OptTag optTag = new OptTag();
		optTag.setTagName(element.tagName());
		Iterator<Attribute> attributes = element.attributes().iterator();
		while (attributes.hasNext()) {
			Attribute attribute = attributes.next();
			optTag.attr(attribute.getKey(), attribute.getValue());
		}
		return optTag;
	}

	private boolean isExcluded(String resourcePath) {
		for (String excludeKeyword : excludeSet) {
			if (resourcePath.contains(excludeKeyword)) {
				return true;
			}
		}
		return false;
	}

	protected static String getRealPath(String path) {
		String contextPath = context.getContextPath();
		// remove context path from the path.
		String jsRelativePath = (path.startsWith(contextPath) ? path.substring(contextPath.length()) : path);

		return context.getRealPath(jsRelativePath);
	}

	protected String merge(Set<String> resourcePaths) {
		long startTime = System.currentTimeMillis();
		StringBuilder mergedContent = new StringBuilder(8192);
		for (String src : resourcePaths) {
			String realPath = getRealPath(src);
			BufferedReader fileReader = null;
			try {
				fileReader = new BufferedReader(new InputStreamReader(new FileInputStream(realPath), charset));
				String line = null;
				while ((line = fileReader.readLine()) != null) {
					mergedContent.append(line).append(NEW_LINE);
				}
			} catch (Exception e) {
				log.warn("Cannot read file " + realPath, e);
			} finally {
				IOUtils.closeQuietly(fileReader);
			}
		}

		String mergedFileName = getMergedFileRelativePath(resourcePaths);
		String mergedFileRealPath = getRealPath(mergedFileName);
		BufferedWriter fileWriter = null;
		try {
			fileWriter = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(mergedFileRealPath), charset));
			fileWriter.write(mergedContent.toString());
		} catch (IOException e) {
			log.warn("Cannot write file " + mergedFileRealPath, e);
		} finally {
			IOUtils.closeQuietly(fileWriter);
			if (log.isDebugEnabled()) {
				log.debug("Merging " + mergedFileName + " takes: " + (System.currentTimeMillis() - startTime));
			}
		}

		/*String mergedFileName = getMergedFileRelativePath(resourcePaths);
		String mergedFileRealPath = getRealPath(mergedFileName);
        OutputStream os = null;
        
        try {
        	os = new FileOutputStream(mergedFileRealPath);
        	IOUtils.closeQuietly(os);
			os = new BufferedOutputStream(new FileOutputStream(mergedFileRealPath, true));
		} catch (FileNotFoundException e) {
			log.error("Cannot write file " + mergedFileRealPath, e);
		}
        
		try {
			for (String src : resourcePaths) {
				String realPath = getRealPath(src);
		        InputStream is = null;
				try {
					is = new BufferedInputStream(new FileInputStream(realPath));
					IOUtils.copy(is, os);
				} catch (IOException e) {
					log.error("Cannot read or write file", e);
				} finally {
					IOUtils.closeQuietly(is);
				}
			}
		} finally {
			IOUtils.closeQuietly(os);
		}*/

		return mergedFileName;
	}

	private String getMergedResourcePath(Set<String> resourcePaths) {
		if (resourcePaths.isEmpty()) {
			return StringUtils.EMPTY;
		} else {
			String mergedElementPath = mergedResourcePaths.get(resourcePaths);
			if (mergedElementPath == null) {
				// usually this is the first time load, since no cache is hit.

				mergedElementPath = merge(resourcePaths);

				if (minify) {
					minify(mergedElementPath);
				}

				if (compress) {
					long startTime = System.currentTimeMillis();
					compress(mergedElementPath);
					if (log.isDebugEnabled()) {
						log.debug("Compressing " + mergedElementPath + " takes: " + (System.currentTimeMillis() - startTime));
					}
				}

				mergedResourcePaths.put(resourcePaths, mergedElementPath);

				if (log.isInfoEnabled()) {
					log.info("Optimized Resource File: " + mergedElementPath);
				}
			}
			return context.getContextPath() + mergedElementPath;
		}
	}

	private static Element createElement(String tagName, Map<String, String> attributeMap) {
		Element mergedElement = new Element(Tag.valueOf(tagName), StringUtils.EMPTY);
		if (attributeMap != null) {
		    for (Map.Entry<String, String> entry : attributeMap.entrySet()) {
				mergedElement.attr(entry.getKey(), entry.getValue());
			}
		}
		return mergedElement;
	}

	protected class OptTag {
		private String tagName;
		private String srcAttributeName;
		private Map<String, String> attributes = new HashMap<String, String>();

		public String getTagName() {
			return tagName;
		}

		public void setTagName(String tagName) {
			this.tagName = tagName;
		}

		public String getSrcAttributeName() {
			return srcAttributeName;
		}

		public void setSrcAttributeName(String srcAttributeName) {
			this.srcAttributeName = srcAttributeName;
		}

		public Map<String, String> getAttributes() {
			return attributes;
		}

		public void setAttributes(Map<String, String> attributes) {
			this.attributes = attributes;
		}

		public Map<String, String> attr(String key, String value) {
			attributes.put(key, value);
			return attributes;
		}

		public String attr(String key) {
			return (attributes == null) ? null : attributes.get(key);
		}
	}

	protected void compress(String resourcePath) {
		String realPath = getRealPath(resourcePath);
		Writer out = null;
		BufferedReader fileReader = null;
		StringBuilder mergedContent = new StringBuilder(8192);
		try {
			fileReader = new BufferedReader(new InputStreamReader(new FileInputStream(realPath), charset));
			String line = null;
			while ((line = fileReader.readLine()) != null) {
				mergedContent.append(line).append(NEW_LINE);
			}
			IOUtils.closeQuietly(fileReader);
			out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(realPath)), charset));
			out.write(mergedContent.toString());
		} catch (Exception e) {
			log.error("Fail to compress with GZip", e);
		} finally {
			IOUtils.closeQuietly(fileReader);
			IOUtils.closeQuietly(out);
		}
	}

	protected String getMergedFilenamePrefix(Set<String> resourcePaths) {
		String prefix = (useServlet) ? SERVLET_MODE_FILE_PREFIX : STATIC_MODE_FILE_PREFIX;
		return prefix + resourcePaths.hashCode();
	}

	protected void minify(String resourcePath) {

	}

	protected abstract String getMergedFileRelativePath(Set<String> resourcePaths);

	protected abstract OptTag getStandardOptTag();

	protected abstract boolean needOptimize(OptTag tag);

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public void setExclude(String exclude) {
		if (StringUtils.isNotBlank(exclude)) {
			for (String excludeKeyword : exclude.split(",")) {
				excludeSet.add(excludeKeyword.trim());
			}
		}
	}

	public void setCharset(String charset) {
		this.charset = charset;
	}

	public Set<String> getExcludeSet() {
		return excludeSet;
	}

	public boolean isEnabled() {
		return enabled;
	}

	public String getCharset() {
		return charset;
	}

	public boolean isMinify() {
		return minify;
	}

	public void setMinify(boolean minify) {
		this.minify = minify;
	}

	public boolean isCompress() {
		return compress;
	}

	public void setCompress(boolean compress) {
		this.compress = compress;
	}

	public boolean isCache() {
		return cache;
	}

	public void setCache(boolean cache) {
		this.cache = cache;
	}

	public boolean isUseServlet() {
		return useServlet;
	}

	public void setUseServlet(boolean useServlet) {
		this.useServlet = useServlet;
	}
}
