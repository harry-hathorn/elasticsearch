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

import org.apache.lucene.document.Field;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentParserUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A helper class for {@link JsonFieldMapper} parses a JSON object
 * and produces an indexable field for each leaf value.
 */
public class JsonFieldParser {
    private final MappedFieldType fieldType;
    private final int ignoreAbove;

    JsonFieldParser(MappedFieldType fieldType,
                    int ignoreAbove) {
        this.fieldType = fieldType;
        this.ignoreAbove = ignoreAbove;
    }

    public List<IndexableField> parse(XContentParser parser) throws IOException {
        XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT,
            parser.currentToken(),
            parser::getTokenLocation);

        List<IndexableField> fields = new ArrayList<>();
        int openObjects = 1;

        while (true) {
            if (openObjects == 0) {
                return fields;
            }

            XContentParser.Token token = parser.nextToken();
            assert token != null;

            if (token == XContentParser.Token.START_OBJECT) {
                openObjects++;
            } else if (token == XContentParser.Token.END_OBJECT) {
                openObjects--;
            } else if (token.isValue()) {
                String value = parser.text();
                addField(value, fields);
            } else if (token == XContentParser.Token.VALUE_NULL) {
                String value = fieldType.nullValueAsString();
                if (value != null) {
                    addField(value, fields);
                }
            }
        }
    }

    private void addField(String value, List<IndexableField> fields) {
        if (value.length() <= ignoreAbove) {
            fields.add(new Field(fieldType.name(), new BytesRef(value), fieldType));
        }
    }
}
