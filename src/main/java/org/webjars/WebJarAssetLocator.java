package org.webjars;

import org.webjars.urlprotocols.UrlProtocolHandler;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locate WebJar assets. The class is thread safe.
 */
public class WebJarAssetLocator {

    /**
     * The webjar package name.
     */
    public static final String WEBJARS_PACKAGE = "META-INF.resources.webjars";

    /**
     * The path to where webjar resources live.
     */
    public static final String WEBJARS_PATH_PREFIX = "META-INF/resources/webjars";

    private static Pattern WEBJAR_EXTRACTOR_PATTERN = Pattern.compile(WEBJARS_PATH_PREFIX + "/([^/]*)/([^/]*)/(.*)$");

    private static void aggregateFile(final File file, final Set<String> aggregatedChildren, final Pattern filterExpr) {
        final String path = file.getPath().replace('\\', '/');
        final String relativePath = path.substring(path.indexOf(WEBJARS_PATH_PREFIX));
        if (filterExpr.matcher(relativePath).matches()) {
            aggregatedChildren.add(relativePath);
        }
    }

    /*
     * Return all {@link URL}s defining {@value WebJarAssetLocator#WEBJARS_PATH_PREFIX} directory, either identifying JAR files or plain directories.
     */
    static Set<URL> listParentURLsWithResource(final ClassLoader[] classLoaders, final String resource) {
        final Set<URL> urls = new HashSet<URL>();
        for (final ClassLoader classLoader : classLoaders) {
            try {
                final Enumeration<URL> enumeration = classLoader.getResources(resource);
                while (enumeration.hasMoreElements()) {
                    urls.add(enumeration.nextElement());
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return urls;
    }

    /*
     * Return all of the resource paths filtered given an expression and a list
     * of class loaders.
     */
    private static Set<String> getAssetPaths(final Pattern filterExpr,
                                             final ClassLoader... classLoaders) {
        final Set<String> assetPaths = new HashSet<String>();
        final Set<URL> urls = listParentURLsWithResource(classLoaders, WEBJARS_PATH_PREFIX);

        ServiceLoader<UrlProtocolHandler> urlProtocolHandlers = ServiceLoader.load(UrlProtocolHandler.class);

        for (final URL url : urls) {
            for (UrlProtocolHandler urlProtocolHandler : urlProtocolHandlers) {
                if (urlProtocolHandler.accepts(url.getProtocol())) {
                    Set<String> assetPathSet = urlProtocolHandler.getAssetPaths(url, filterExpr, classLoaders);
                    if(assetPathSet != null) {
                        assetPaths.addAll(assetPathSet);
                        break;
                    }
                }
            }
        }

        return assetPaths;
    }

    /**
     * Return a map that can be used to perform index lookups of partial file
     * paths. This index constitutes a key that is the reverse form of the path
     * it relates to. Thus if a partial lookup needs to be performed from the
     * rightmost path components then the key to access can be expressed easily
     * e.g. the path "a/b" would be the map tuple "b/a" -&gt; "a/b". If we need to
     * look for an asset named "a" without knowing the full path then we can
     * perform a partial lookup on the sorted map.
     *
     * @param filterExpr   the regular expression to be used to filter resources that
     *                     will be included in the index.
     * @param classLoaders the class loaders to be considered for loading the resources
     *                     from.
     * @return the index.
     */
    public static SortedMap<String, String> getFullPathIndex(
            final Pattern filterExpr, final ClassLoader... classLoaders) {

        final Set<String> assetPaths = getAssetPaths(filterExpr, classLoaders);

        final SortedMap<String, String> assetPathIndex = new TreeMap<String, String>();
        for (final String assetPath : assetPaths) {
            assetPathIndex.put(reversePath(assetPath), assetPath);
        }

        return assetPathIndex;
    }

    /*
     * Make paths like aa/bb/cc = cc/bb/aa.
     */
    private static String reversePath(String assetPath) {
        final String[] assetPathComponents = assetPath.split("/");
        final StringBuilder reversedAssetPath = new StringBuilder();
        for (int i = assetPathComponents.length - 1; i >= 0; --i) {
            if (reversedAssetPath.length() > 0) {
                reversedAssetPath.append('/');
            }
            reversedAssetPath.append(assetPathComponents[i]);
        }
        return reversedAssetPath.toString();
    }

    final SortedMap<String, String> fullPathIndex;

    /**
     * Convenience constructor that will form a locator for all resources on the
     * current class path.
     */
    public WebJarAssetLocator() {
        this(getFullPathIndex(Pattern.compile(".*"),
                WebJarAssetLocator.class.getClassLoader()));
    }

    /**
     * Establish a locator given an index that it should use.
     *
     * @param fullPathIndex the index to use.
     */
    public WebJarAssetLocator(final SortedMap<String, String> fullPathIndex) {
        this.fullPathIndex = fullPathIndex;
    }

    private String throwNotFoundException(final String partialPath) {
        throw new IllegalArgumentException(
                partialPath
                        + " could not be found. Make sure you've added the corresponding WebJar and please check for typos."
        );
    }

    /**
     * Given a distinct path within the WebJar index passed in return the full
     * path of the resource.
     *
     * @param partialPath the path to return e.g. "jquery.js" or "abc/someother.js".
     *                    This must be a distinct path within the index passed in.
     * @return a fully qualified path to the resource.
     */
    public String getFullPath(final String partialPath) {
        return getFullPath(fullPathIndex, partialPath);
    }

    /**
     * Returns the full path of an asset within a specific WebJar
     *
     * @param webjar      The id of the WebJar to search
     * @param partialPath The partial path to look for
     * @return a fully qualified path to the resource
     */
    public String getFullPath(final String webjar, final String partialPath) {
        return getFullPath(filterPathIndexByPrefix(fullPathIndex, WEBJARS_PATH_PREFIX + "/" + webjar), partialPath);
    }

    private String getFullPath(SortedMap<String, String> pathIndex, String partialPath) {
        final String reversePartialPath = reversePath(prependSlash(partialPath));

        final SortedMap<String, String> fullPathTail = pathIndex.tailMap(reversePartialPath);

        if (fullPathTail.size() == 0) {
            throwNotFoundException(partialPath);
        }

        final Iterator<Entry<String, String>> fullPathTailIter = fullPathTail
                .entrySet().iterator();
        final Entry<String, String> fullPathEntry = fullPathTailIter.next();
        if (!fullPathEntry.getKey().startsWith(reversePartialPath)) {
            throwNotFoundException(partialPath);
        }
        final String fullPath = fullPathEntry.getValue();

        if (fullPathTailIter.hasNext()
                && fullPathTailIter.next().getKey()
                .startsWith(reversePartialPath)) {
            throw new MultipleMatchesException(
                    "Multiple matches found for "
                            + partialPath
                            + ". Please provide a more specific path, for example by including a version number."
            );
        }

        return fullPath;
    }

    private SortedMap<String, String> filterPathIndexByPrefix(SortedMap<String, String> pathIndex, String prefix) {
        SortedMap<String, String> filteredPathIndex = new TreeMap<String, String>();
        for (String key : pathIndex.keySet()) {
            String value = pathIndex.get(key);
            if (value.startsWith(prefix)) {
                filteredPathIndex.put(key, value);
            }
        }
        return filteredPathIndex;
    }

    /**
     * Prepends a forward slash to a path if there isn't already a forward slash at the front of the path
     *
     * @param path the old path
     * @return the new path
     */
    private String prependSlash(final String path) {
        if (path.startsWith("/")) {
            return path;
        } else {
            return "/" + path;
        }
    }

    public SortedMap<String, String> getFullPathIndex() {
        return fullPathIndex;
    }

    /**
     * List assets within a folder.
     *
     * @param folderPath the root path to the folder. Must begin with '/'.
     * @return a set of folder paths that match.
     */
    public Set<String> listAssets(final String folderPath) {
        final Collection<String> allAssets = fullPathIndex.values();
        final Set<String> assets = new HashSet<String>();
        final String prefix = WEBJARS_PATH_PREFIX + folderPath;
        for (final String asset : allAssets) {
            if (asset.startsWith(prefix)) {
                assets.add(asset);
            }
        }
        return assets;
    }

    /**
     * @return A map of the WebJars based on the files in the CLASSPATH where the key is the artifactId and the value is the version
     */
    public Map<String, String> getWebJars() {

        Map<String, String> webjars = new HashMap<String, String>();

        for (String webjarFile : fullPathIndex.values()) {

            Entry<String, String> webjar = getWebJar(webjarFile);

            if ((webjar != null) && (!webjars.containsKey(webjar.getKey()))) {
                webjars.put(webjar.getKey(), webjar.getValue());
            }
        }

        return webjars;
    }

    /**
     * @param path The full WebJar path
     * @return A WebJar tuple (Entry) with key = id and value = version
     */
    public static Entry<String, String> getWebJar(String path) {
        Matcher matcher = WEBJAR_EXTRACTOR_PATTERN.matcher(path);
        if (matcher.find()) {
            String id = matcher.group(1);
            String version = matcher.group(2);
            return new AbstractMap.SimpleEntry<String, String>(id, version);
        } else {
            // not a legal WebJar file format
            return null;
        }
    }

}
