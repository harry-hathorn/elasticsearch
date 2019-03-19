/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.apache.lucene.analysis.core.WhitespaceAnalyzer;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.lucene.Lucene;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.index.analysis.AnalyzerScope;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.query.QueryShardContext;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.index.mapper.TypeParsers.parseField;

/**
 * A field mapper that accepts a JSON object and flattens it into a single field. This data type
 * can be a useful alternative to an 'object' mapping when the object has a large, unknown set
 * of keys.
 *
 * Currently the mapper extracts all leaf values of the JSON object, converts them to their text
 * representations, and indexes each one as a keyword. It creates both a 'keyed' version of the token
 * to allow searches on particular key-value pairs, as well as a 'root' token without the key
 *
 * As an example, given a json field called 'json_field' and the following input
 *
 * {
 *   "json_field: {
 *     "key1": "some value",
 *     "key2": {
 *       "key3": true
 *     }
 *   }
 * }
 *
 * the mapper will produce untokenized string fields called "json_field" with values "some value" and "true",
 * as well as string fields called "json_field._keyed" with values "key\0some value" and "key2.key3\0true".
 *
 * Note that \0 is a reserved separator character, and cannot be used in the keys of the JSON object
 * (see {@link JsonFieldParser#SEPARATOR}).
 */
public final class JsonFieldMapper extends FieldMapper {

    public static final String CONTENT_TYPE = "json";
    public static final NamedAnalyzer WHITESPACE_ANALYZER = new NamedAnalyzer(
        "whitespace", AnalyzerScope.INDEX, new WhitespaceAnalyzer());
    public static final String KEYED_FIELD_SUFFIX = "._keyed";

    private static class Defaults {
        public static final MappedFieldType FIELD_TYPE = new JsonFieldType();

        static {
            FIELD_TYPE.setTokenized(false);
            FIELD_TYPE.setOmitNorms(true);
            FIELD_TYPE.setStored(false);
            FIELD_TYPE.setIndexOptions(IndexOptions.DOCS);
            FIELD_TYPE.freeze();
        }

        public static final int IGNORE_ABOVE = Integer.MAX_VALUE;
    }

    public static class Builder extends FieldMapper.Builder<Builder, JsonFieldMapper> {
        private int ignoreAbove = Defaults.IGNORE_ABOVE;

        public Builder(String name) {
            super(name, Defaults.FIELD_TYPE, Defaults.FIELD_TYPE);
            builder = this;
        }

        @Override
        public JsonFieldType fieldType() {
            return (JsonFieldType) super.fieldType();
        }

        @Override
        public Builder indexOptions(IndexOptions indexOptions) {
            if (indexOptions.compareTo(IndexOptions.DOCS_AND_FREQS) > 0) {
                throw new IllegalArgumentException("The [" + CONTENT_TYPE
                    + "] field does not support positions, got [index_options]="
                    + indexOptionToString(indexOptions));
            }
            return super.indexOptions(indexOptions);
        }

        public Builder ignoreAbove(int ignoreAbove) {
            if (ignoreAbove < 0) {
                throw new IllegalArgumentException("[ignore_above] must be positive, got " + ignoreAbove);
            }
            this.ignoreAbove = ignoreAbove;
            return this;
        }

        public Builder splitQueriesOnWhitespace(boolean splitQueriesOnWhitespace) {
            fieldType().setSplitQueriesOnWhitespace(splitQueriesOnWhitespace);
            return builder;
        }

        @Override
        public Builder addMultiField(Mapper.Builder mapperBuilder) {
            throw new UnsupportedOperationException("[fields] is not supported for [" + CONTENT_TYPE + "] fields.");
        }

        @Override
        public Builder copyTo(CopyTo copyTo) {
            throw new UnsupportedOperationException("[copy_to] is not supported for [" + CONTENT_TYPE + "] fields.");
        }

        @Override
        public Builder store(boolean store) {
            throw new UnsupportedOperationException("[store] is not currently supported for [" +
                CONTENT_TYPE + "] fields.");
        }

        @Override
        public JsonFieldMapper build(BuilderContext context) {
            setupFieldType(context);
            if (fieldType().splitQueriesOnWhitespace()) {
                fieldType().setSearchAnalyzer(WHITESPACE_ANALYZER);
            }
            return new JsonFieldMapper(name, fieldType, defaultFieldType,
                ignoreAbove, context.indexSettings());
        }
    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder<?,?> parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {
            JsonFieldMapper.Builder builder = new JsonFieldMapper.Builder(name);
            parseField(builder, name, node, parserContext);
            for (Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator(); iterator.hasNext();) {
                Map.Entry<String, Object> entry = iterator.next();
                String propName = entry.getKey();
                Object propNode = entry.getValue();
                if (propName.equals("ignore_above")) {
                    builder.ignoreAbove(XContentMapValues.nodeIntegerValue(propNode, -1));
                    iterator.remove();
                } else if (propName.equals("null_value")) {
                    if (propNode == null) {
                        throw new MapperParsingException("Property [null_value] cannot be null.");
                    }
                    builder.nullValue(propNode.toString());
                    iterator.remove();
                } else if (propName.equals("split_queries_on_whitespace")) {
                    builder.splitQueriesOnWhitespace
                        (XContentMapValues.nodeBooleanValue(propNode, "split_queries_on_whitespace"));
                    iterator.remove();
                }
            }
            return builder;
        }
    }

    public static final class JsonFieldType extends StringFieldType {
        private boolean splitQueriesOnWhitespace;

        public JsonFieldType() {
            setIndexAnalyzer(Lucene.KEYWORD_ANALYZER);
            setSearchAnalyzer(Lucene.KEYWORD_ANALYZER);
        }

        private JsonFieldType(JsonFieldType ref) {
            super(ref);
            this.splitQueriesOnWhitespace = ref.splitQueriesOnWhitespace;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;
            JsonFieldType that = (JsonFieldType) o;
            return splitQueriesOnWhitespace == that.splitQueriesOnWhitespace;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), splitQueriesOnWhitespace);
        }

        public JsonFieldType clone() {
            return new JsonFieldType(this);
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        public boolean splitQueriesOnWhitespace() {
            return splitQueriesOnWhitespace;
        }

        public void setSplitQueriesOnWhitespace(boolean splitQueriesOnWhitespace) {
            checkIfFrozen();
            this.splitQueriesOnWhitespace = splitQueriesOnWhitespace;
        }

        @Override
        public Query existsQuery(QueryShardContext context) {
            return new TermQuery(new Term(FieldNamesFieldMapper.NAME, name()));
        }

        @Override
        public Query fuzzyQuery(Object value, Fuzziness fuzziness, int prefixLength, int maxExpansions,
                                boolean transpositions) {
            throw new UnsupportedOperationException("[fuzzy] queries are not currently supported on [" +
                CONTENT_TYPE + "] fields.");
        }

        @Override
        public Query regexpQuery(String value, int flags, int maxDeterminizedStates,
                                 MultiTermQuery.RewriteMethod method, QueryShardContext context) {
            throw new UnsupportedOperationException("[regexp] queries are not currently supported on [" +
                CONTENT_TYPE + "] fields.");
        }

        @Override
        public Query wildcardQuery(String value,
                                   MultiTermQuery.RewriteMethod method,
                                   QueryShardContext context) {
            throw new UnsupportedOperationException("[wildcard] queries are not currently supported on [" +
                CONTENT_TYPE + "] fields.");
        }

        @Override
        public Object valueForDisplay(Object value) {
            if (value == null) {
                return null;
            }
            BytesRef binaryValue = (BytesRef) value;
            return binaryValue.utf8ToString();
        }
    }

    private final JsonFieldParser fieldParser;
    private int ignoreAbove;

    private JsonFieldMapper(String simpleName,
                            MappedFieldType fieldType,
                            MappedFieldType defaultFieldType,
                            int ignoreAbove,
                            Settings indexSettings) {
        super(simpleName, fieldType, defaultFieldType, indexSettings, MultiFields.empty(), CopyTo.empty());
        assert fieldType.indexOptions().compareTo(IndexOptions.DOCS_AND_FREQS) <= 0;

        this.ignoreAbove = ignoreAbove;
        this.fieldParser = new JsonFieldParser(fieldType, ignoreAbove);
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    protected void doMerge(Mapper mergeWith) {
        super.doMerge(mergeWith);
        this.ignoreAbove = ((JsonFieldMapper) mergeWith).ignoreAbove;
    }

    @Override
    protected JsonFieldMapper clone() {
        return (JsonFieldMapper) super.clone();
    }

    @Override
    public JsonFieldType fieldType() {
        return (JsonFieldType) super.fieldType();
    }

    @Override
    protected void parseCreateField(ParseContext context, List<IndexableField> fields) throws IOException {
        if (context.parser().currentToken() == XContentParser.Token.VALUE_NULL) {
            return;
        }

        if (fieldType().indexOptions() != IndexOptions.NONE || fieldType().stored()) {
            fields.addAll(fieldParser.parse(context.parser()));
            createFieldNamesField(context, fields);
        } else {
            context.parser().skipChildren();
        }
    }

    @Override
    protected void doXContentBody(XContentBuilder builder, boolean includeDefaults, Params params) throws IOException {
        super.doXContentBody(builder, includeDefaults, params);

        if (includeDefaults || fieldType().nullValue() != null) {
            builder.field("null_value", fieldType().nullValue());
        }

        if (includeDefaults || ignoreAbove != Defaults.IGNORE_ABOVE) {
            builder.field("ignore_above", ignoreAbove);
        }

        if (includeDefaults || fieldType().splitQueriesOnWhitespace()) {
            builder.field("split_queries_on_whitespace", fieldType().splitQueriesOnWhitespace());
        }
    }
}
