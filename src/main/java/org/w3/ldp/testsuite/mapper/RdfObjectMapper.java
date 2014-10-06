package org.w3.ldp.testsuite.mapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.w3.ldp.testsuite.http.MediaTypes;
import org.w3.ldp.testsuite.matcher.HeaderMatchers;

import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.rdf.model.RDFWriter;
import com.jayway.restassured.mapper.ObjectMapper;
import com.jayway.restassured.mapper.ObjectMapperDeserializationContext;
import com.jayway.restassured.mapper.ObjectMapperSerializationContext;

public class RdfObjectMapper implements ObjectMapper {

	private String baseURI;

	public RdfObjectMapper() {
		this.baseURI = "";
	}

	public RdfObjectMapper(String baseURI) {
		this.baseURI = baseURI;
	}

	private String getLang(String mediaType) {
		if (HeaderMatchers.isTurtleCompatibleContentType().matches(mediaType)) {
			return "TURTLE";
		} else if (MediaTypes.APPLICATION_RDF_XML.equals(mediaType)) {
			return "RDF/XML";
		} else if (MediaTypes.APPLICATION_JSON.equals(mediaType) ||
				MediaTypes.APPLICATION_LD_JSON.equals(mediaType)) {
			return "JSON-LD";
		}

		throw new IllegalArgumentException("Unsupported media type: " + mediaType);
	}

	@Override
	public Object deserialize(ObjectMapperDeserializationContext context) {
		InputStream input = context.getDataToDeserialize().asInputStream();
		Model m = ModelFactory.createDefaultModel();
		m.read(input, baseURI, getLang(context.getContentType()));
		return m;
	}

	@Override
	public Object serialize(ObjectMapperSerializationContext context) {
		Model model = context.getObjectToSerializeAs(Model.class);
		ByteArrayOutputStream out = new ByteArrayOutputStream();

		String lang = getLang(context.getContentType());
		RDFWriter rdfWriter = model.getWriter(lang);
		rdfWriter.setProperty("relativeURIs", "same-document");
		rdfWriter.setProperty("allowBadURIs", "true");
		rdfWriter.write(model, out, baseURI);

		if ("JSON-LD".equals(lang)) {
			// Do some additional processing of the JSON-LD content to remove the
			// unwanted @graph property. Unfortunately there is currently no
			// option to control this in Jena itself.
			try {
				JsonObject json = JSON.parse(out.toString("UTF-8"));
				JsonValue graph = json.get("@graph");
				JsonValue jsonContext = json.get("@context");
				if (graph != null && graph.isArray()) {
					out = new ByteArrayOutputStream();
					JsonArray a = graph.getAsArray();

					if (a.size() == 1) {
						// If size is 1, @graph isn't necessary.
						JsonObject content = a.get(0).getAsObject();
						if (jsonContext != null) {
							// Preserve the context if it's there.
							content.put("@context", jsonContext);
						}

						JSON.write(out, content);
					} else {
						// Remove graph ID urn:x-arq:DefaultGraphNode. If @id is
						// left out, it is the implicit default graph.
						// See https://issues.apache.org/jira/browse/JENA-794
						json.remove("@id");
						JSON.write(out, json);
					}
				}
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
		}

		return out.toByteArray();
	}
}
