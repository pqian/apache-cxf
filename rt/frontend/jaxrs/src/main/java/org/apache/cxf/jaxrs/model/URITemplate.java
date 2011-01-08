/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.jaxrs.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.ws.rs.Path;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.PathSegment;

import org.apache.cxf.jaxrs.utils.HttpUtils;
import org.apache.cxf.jaxrs.utils.JAXRSUtils;

public final class URITemplate {

    public static final String TEMPLATE_PARAMETERS = "jaxrs.template.parameters";
    public static final String LIMITED_REGEX_SUFFIX = "(/.*)?";
    public static final String FINAL_MATCH_GROUP = "FINAL_MATCH_GROUP";
    private static final String DEFAULT_PATH_VARIABLE_REGEX = "([^/]+?)";
    private static final String CHARACTERS_TO_ESCAPE = ".*";

    private final String template;
    private final List<String> variables = new ArrayList<String>();
    private final List<String> customVariables = new ArrayList<String>();
    private final Pattern templateRegexPattern;
    private final String literals;
    private final List<UriChunk> uriChunks;

    public URITemplate(String theTemplate) {
        template = theTemplate;
        StringBuilder literalChars = new StringBuilder();
        StringBuilder patternBuilder = new StringBuilder();
        CurlyBraceTokenizer tok = new CurlyBraceTokenizer(template);
        uriChunks = new ArrayList<UriChunk>();
        while (tok.hasNext()) {
            String templatePart = tok.next();
            UriChunk chunk = UriChunk.createUriChunk(templatePart);
            uriChunks.add(chunk);
            if (chunk instanceof Literal) {
                String encodedValue = HttpUtils.encodePartiallyEncoded(chunk.getValue(), false);
                String substr = escapeCharacters(encodedValue);
                literalChars.append(substr);
                patternBuilder.append(substr);
            } else if (chunk instanceof Variable) {
                Variable var = (Variable)chunk;
                variables.add(var.getName());
                if (var.getPattern() != null) {
                    customVariables.add(var.getName());
                    patternBuilder.append('(');
                    patternBuilder.append(var.getPattern());
                    patternBuilder.append(')');
                } else {
                    patternBuilder.append(DEFAULT_PATH_VARIABLE_REGEX);
                }
            }
        }
        literals = literalChars.toString();

        int endPos = patternBuilder.length() - 1;
        boolean endsWithSlash = (endPos >= 0) ? patternBuilder.charAt(endPos) == '/' : false;
        if (endsWithSlash) {
            patternBuilder.deleteCharAt(endPos);
        }
        patternBuilder.append(LIMITED_REGEX_SUFFIX);

        templateRegexPattern = Pattern.compile(patternBuilder.toString());
    }

    public String getLiteralChars() {
        return literals;
    }

    public String getValue() {
        return template;
    }

    /**
     * List of all variables in order of appearance in template.
     * 
     * @return unmodifiable list of variable names w/o patterns, e.g. for "/foo/{v1:\\d}/{v2}" returned list
     *         is ["v1","v2"].
     */
    public List<String> getVariables() {
        return Collections.unmodifiableList(variables);
    }

    /**
     * List of variables with patterns (regexps). List is subset of elements from {@link #getVariables()}.
     * 
     * @return unmodifiable list of variables names w/o patterns.
     */
    public List<String> getCustomVariables() {
        return Collections.unmodifiableList(customVariables);
    }

    private static String escapeCharacters(String expression) {

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            sb.append(isReservedCharacter(ch) ? "\\" + ch : ch);
        }
        return sb.toString();
    }

    private static boolean isReservedCharacter(char ch) {
        return CHARACTERS_TO_ESCAPE.indexOf(ch) != -1;
    }

    public boolean match(String uri, MultivaluedMap<String, String> templateVariableToValue) {

        if (uri == null) {
            return (templateRegexPattern == null) ? true : false;
        }

        if (templateRegexPattern == null) {
            return false;
        }

        Matcher m = templateRegexPattern.matcher(uri);
        if (!m.matches()) {
            if (uri.contains(";")) {
                // we might be trying to match one or few path segments
                // containing matrix
                // parameters against a clear path segment as in @Path("base").
                List<PathSegment> pList = JAXRSUtils.getPathSegments(template, false);
                List<PathSegment> uList = JAXRSUtils.getPathSegments(uri, false);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < uList.size(); i++) {
                    sb.append('/');
                    if (pList.size() > i && pList.get(i).getPath().indexOf('{') == -1) {
                        sb.append(uList.get(i).getPath());
                    } else {
                        sb.append(HttpUtils.fromPathSegment(uList.get(i)));
                    }
                }
                uri = sb.toString();
                m = templateRegexPattern.matcher(uri);
                if (!m.matches()) {
                    return false;
                }
            } else {
                return false;
            }
        }

        // Assign the matched template values to template variables
        int groupCount = m.groupCount();
        
        int i = 1;
        for (String name : variables) {
            while (i <= groupCount) {
                String value = m.group(i++);
                if (value == null || value.length() == 0 && i < groupCount) {
                    continue;
                }
                templateVariableToValue.add(name, value);
                break;
            }
        }
        // The right hand side value, might be used to further resolve
        // sub-resources.
        
        String finalGroup = i > groupCount ? "/" : m.group(groupCount);
        templateVariableToValue.putSingle(FINAL_MATCH_GROUP, finalGroup == null ? "/" : finalGroup);

        return true;
    }

    /**
     * Substitutes template variables with listed values. List of values is counterpart for
     * {@link #getVariables() list of variables}. When list of value is shorter than variables substitution
     * is partial. When variable has pattern, value must fit to pattern, otherwise
     * {@link IllegalArgumentException} is thrown.
     * <p>
     * Example1: for template "/{a}/{b}/{a}" {@link #getVariables()} returns "[a, b, a]"; providing here list
     * of value "[foo, bar, baz]" results with "/foo/bar/baz".
     * <p>
     * Example2: for template "/{a}/{b}/{a}" providing list of values "[foo]" results with "/foo/{b}/{a}".
     * 
     * @param values values for variables
     * @return template with bound variables.
     * @throws IllegalArgumentException when values is null, any value does not match pattern etc.
     */
    public String substitute(List<String> values) throws IllegalArgumentException {
        if (values == null) {
            throw new IllegalArgumentException("values is null");
        }
        Iterator<String> iter = values.iterator();
        StringBuilder sb = new StringBuilder();
        for (UriChunk chunk : uriChunks) {
            if (chunk instanceof Variable) {
                Variable var = (Variable)chunk;
                if (iter.hasNext()) {
                    String value = iter.next();
                    if (!var.matches(value)) {
                        throw new IllegalArgumentException("Value '" + value + "' does not match variable "
                                                           + var.getName() + " with pattern "
                                                           + var.getPattern());
                    }
                    sb.append(value);
                } else {
                    sb.append(var);
                }
            } else {
                sb.append(chunk);
            }
        }
        return sb.toString();
    }

    /**
     * Substitutes template variables with mapped values. Variables are mapped to values; if not all variables
     * are bound result will still contain variables. Note that all variables with the same name are replaced
     * by one value.
     * <p>
     * Example: for template "/{a}/{b}/{a}" {@link #getVariables()} returns "[a, b, a]"; providing here
     * mapping "[a: foo, b: bar]" results with "/foo/bar/foo" (full substitution) and for mapping "[b: baz]"
     * result is "{a}/baz/{a}" (partial substitution).
     * 
     * @param valuesMap map variables to their values; on each value Object.toString() is called.
     * @return template with bound variables.
     */
    public String substitute(Map<String, ? extends Object> valuesMap) throws IllegalArgumentException {
        if (valuesMap == null) {
            throw new IllegalArgumentException("valuesMap is null");
        }
        StringBuilder sb = new StringBuilder();
        for (UriChunk chunk : uriChunks) {
            if (chunk instanceof Variable) {
                Variable var = (Variable)chunk;
                Object value = valuesMap.get(var.getName());
                if (value != null) {
                    String sval = value.toString();
                    if (!var.matches(sval)) {
                        throw new IllegalArgumentException("Value '" + sval + "' does not match variable "
                                                           + var.getName() + " with pattern "
                                                           + var.getPattern());
                    }
                    sb.append(value);
                } else {
                    throw new IllegalArgumentException("Template variable " + var.getName() 
                        + " has no matching value"); 
                }
            } else {
                sb.append(chunk);
            }
        }
        return sb.toString();
    }

    /**
     * Encoded literal characters surrounding template variables, 
     * ex. "a {id} b" will be encoded to "a%20{id}%20b" 
     * @return encoded value
     */
    public String encodeLiteralCharacters() {
        final float encodedRatio = 1.5f;
        StringBuilder sb = new StringBuilder((int)(encodedRatio * template.length()));
        for (UriChunk chunk : uriChunks) {
            String val = chunk.getValue();
            if (chunk instanceof Literal) {
                sb.append(HttpUtils.encodePartiallyEncoded(val, false));
            } else { 
                sb.append(val);
            }
        }
        return sb.toString();
    }
    
    public static URITemplate createTemplate(Path path) {

        return createTemplate(path == null ? null : path.value());
    }
    
    public static URITemplate createTemplate(String pathValue) {

        if (pathValue == null) {
            return new URITemplate("/");
        }

        if (!pathValue.startsWith("/")) {
            pathValue = "/" + pathValue;
        }

        return new URITemplate(pathValue);
    }

    public static int compareTemplates(URITemplate t1, URITemplate t2) {
        String l1 = t1.getLiteralChars();
        String l2 = t2.getLiteralChars();
        if (!l1.equals(l2)) {
            // descending order
            return l1.length() < l2.length() ? 1 : -1;
        }

        int g1 = t1.getVariables().size();
        int g2 = t2.getVariables().size();
        // descending order
        int result = g1 < g2 ? 1 : g1 > g2 ? -1 : 0;
        if (result == 0) {
            int gCustom1 = t1.getCustomVariables().size();
            int gCustom2 = t2.getCustomVariables().size();
            if (gCustom1 != gCustom2) {
                // descending order
                return gCustom1 < gCustom2 ? 1 : -1;
            }
        }
        return result;
    }

    /**
     * Stringified part of URI. Chunk is not URI segment; chunk can span over multiple URI segments or one URI
     * segments can have multiple chunks. Chunk is used to decompose URI of {@link URITemplate} into literals
     * and variables. Example: "foo/bar/{baz}{blah}" is decomposed into chunks: "foo/bar", "{baz}" and
     * "{blah}".
     */
    private abstract static class UriChunk {
        /**
         * Creates object form string.
         * 
         * @param uriChunk stringified uri chunk
         * @return If param has variable form then {@link Variable} instance is created, otherwise chunk is
         *         treated as {@link Literal}.
         */
        public static UriChunk createUriChunk(String uriChunk) {
            if (uriChunk == null || "".equals(uriChunk)) {
                throw new IllegalArgumentException("uriChunk is empty");
            }
            try {
                return new Variable(uriChunk);
            } catch (IllegalArgumentException e) {
                return new Literal(uriChunk);
            }
        }

        public abstract String getValue();

        @Override
        public String toString() {
            return getValue();
        }
    }

    private static final class Literal extends UriChunk {
        private String value;

        public Literal(String uriChunk) {
            if (uriChunk == null || "".equals(uriChunk)) {
                throw new IllegalArgumentException("uriChunk is empty");
            }
            value = uriChunk;
        }

        @Override
        public String getValue() {
            return value;
        }

    }

    /**
     * Variable of URITemplate. Variable has either "{varname:pattern}" syntax or "{varname}".
     */
    private static final class Variable extends UriChunk {
        private static final Pattern VARIABLE_PATTERN = Pattern.compile("(\\w[-\\w\\.]*[ ]*)(\\:(.+))?");
        private String name;
        private Pattern pattern;

        /**
         * Creates variable from stringified part of URI.
         * 
         * @param uriChunk chunk that depicts variable
         * @throws IllegalArgumentException when param is null, empty or does not have variable syntax.
         * @throws PatternSyntaxException when pattern of variable has wrong syntax.
         */
        public Variable(String uriChunk) throws IllegalArgumentException, PatternSyntaxException {
            if (uriChunk == null || "".equals(uriChunk)) {
                throw new IllegalArgumentException("uriChunk is empty");
            }
            if (CurlyBraceTokenizer.insideBraces(uriChunk)) {
                uriChunk = CurlyBraceTokenizer.stripBraces(uriChunk).trim();
                Matcher matcher = VARIABLE_PATTERN.matcher(uriChunk);
                if (matcher.matches()) {
                    name = matcher.group(1).trim();
                    if (matcher.group(2) != null && matcher.group(3) != null) {
                        String patternExpression = matcher.group(3).trim();
                        pattern = Pattern.compile(patternExpression);
                    }
                    return;
                }
            }
            throw new IllegalArgumentException("not a variable syntax");
        }

        public String getName() {
            return name;
        }

        public String getPattern() {
            return pattern != null ? pattern.pattern() : null;
        }

        /**
         * Checks whether value matches variable. If variable has pattern its checked against, otherwise true
         * is returned.
         * 
         * @param value value of variable
         * @return true if value is valid for variable, false otherwise.
         */
        public boolean matches(String value) {
            if (pattern == null) {
                return true;
            } else {
                return pattern.matcher(value).matches();
            }
        }

        @Override
        public String getValue() {
            if (pattern != null) {
                return "{" + name + ":" + pattern + "}";
            } else {
                return "{" + name + "}";
            }
        }
    }
    
    /**
     * Splits string into parts inside and outside curly braces. Nested curly braces are ignored and treated
     * as part inside top-level curly braces. Example: string "foo{bar{baz}}blah" is split into three tokens,
     * "foo","{bar{baz}}" and "blah". When closed bracket is missing, whole unclosed part is returned as one
     * token, e.g.: "foo{bar" is split into "foo" and "{bar". When opening bracket is missing, closing
     * bracket is ignored and taken as part of current token e.g.: "foo{bar}baz}blah" is split into "foo",
     * "{bar}" and "baz}blah".
     * <p>
     * This is helper class for {@link URITemplate} that enables recurring literals appearing next to regular
     * expressions e.g. "/foo/{zipcode:[0-9]{5}}/". Nested expressions with closed sections, like open-closed
     * brackets causes expression to be out of regular grammar (is context-free grammar) which are not
     * supported by Java regexp version.
     * 
     * @author amichalec
     * @version $Rev$
     */
    static class CurlyBraceTokenizer {

        private List<String> tokens = new ArrayList<String>();
        private int tokenIdx;

        public CurlyBraceTokenizer(String string) {
            boolean outside = true;
            int level = 0;
            int lastIdx = 0;
            int idx;
            for (idx = 0; idx < string.length(); idx++) {
                if (string.charAt(idx) == '{') {
                    if (outside) {
                        if (lastIdx < idx) {
                            tokens.add(string.substring(lastIdx, idx));
                        }
                        lastIdx = idx;
                        outside = false;
                    } else {
                        level++;
                    }
                } else if (string.charAt(idx) == '}' && !outside) {
                    if (level > 0) {
                        level--;
                    } else {
                        if (lastIdx < idx) {
                            tokens.add(string.substring(lastIdx, idx + 1));
                        }
                        lastIdx = idx + 1;
                        outside = true;
                    }
                }
            }
            if (lastIdx < idx) {
                tokens.add(string.substring(lastIdx, idx));
            }
        }

        /**
         * Token is enclosed by curly braces.
         * 
         * @param token
         *            text to verify
         * @return true if enclosed, false otherwise.
         */
        public static boolean insideBraces(String token) {
            return token.charAt(0) == '{' && token.charAt(token.length() - 1) == '}';
        }

        /**
         * Strips token from enclosed curly braces. If token is not enclosed method
         * has no side effect.
         * 
         * @param token
         *            text to verify
         * @return text stripped from curly brace begin-end pair.
         */
        public static String stripBraces(String token) {
            if (insideBraces(token)) {
                return token.substring(1, token.length() - 1);
            } else {
                return token;
            }
        }

        public boolean hasNext() {
            return tokens.size() > tokenIdx;
        }

        public String next() {
            if (hasNext()) {
                return tokens.get(tokenIdx++);
            } else {
                throw new IllegalStateException("no more elements");
            }
        }
    }
}


