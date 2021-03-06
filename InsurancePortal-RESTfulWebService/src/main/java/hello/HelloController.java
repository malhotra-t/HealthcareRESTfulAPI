package hello;

import java.io.BufferedReader;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.everit.json.schema.Schema;
import org.everit.json.schema.ValidationException;
import org.everit.json.schema.loader.SchemaLoader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import redis.clients.jedis.Jedis;

@RestController
public class HelloController {
	Jedis jedis = new Jedis("localhost", 6379);

	// @RequestMapping(method = RequestMethod.POST, consumes =
	// "application/json", path = "/schema")
	// public String createSchema(HttpServletRequest request,
	// HttpServletResponse resp) {
	//
	// System.out.println("start of createSchema");
	//
	// StringBuffer jsonStringBuffer = new StringBuffer();
	// String line = null;
	// try {
	// BufferedReader reader = request.getReader();
	// while ((line = reader.readLine()) != null) {
	// jsonStringBuffer.append(line);
	// }
	//
	// } catch (Exception e) {
	// e.printStackTrace();
	// }
	// System.out.println(jsonStringBuffer);
	//
	// String jsonData = jsonStringBuffer.toString();
	//
	// org.json.JSONObject incJsonObj = new org.json.JSONObject(jsonData);
	//
	// String entityTitle = incJsonObj.get("title").toString();
	// System.out.println("extracted value of title: " + entityTitle);
	// String schemaKey = "schema_" + entityTitle;
	// jedis.set(schemaKey, jsonData);
	// System.out.println("end of createSchema");
	// return schemaKey;
	// }

	// step 1 - allow json input
	@RequestMapping(method = RequestMethod.POST, consumes = "application/json", path = "/{entitySchema}")
	public String createEntityOrSchema(@PathVariable String entitySchema, HttpServletRequest request,
			HttpServletResponse resp) {

		String bearerToken = request.getHeader("Authorization");
		System.out.println("bearerToken" + bearerToken);

		if (bearerToken == null || bearerToken.isEmpty()) {
			resp.setStatus(HttpStatus.UNAUTHORIZED.value());
			return "";
		}
		String decrytptedVal = Authentication.decrypt("secretkeytanyamalhotra", bearerToken);
		System.out.println("decrytptedVal" + decrytptedVal);
		if (decrytptedVal.isEmpty() || !decrytptedVal.equals("validuser23")) {
			resp.setStatus(HttpStatus.UNAUTHORIZED.value());
			return "";
		}

		// step 2 - read json and parse it using json simple

		StringBuffer jsonStringBuffer = new StringBuffer();
		String line = null;
		try {
			BufferedReader reader = request.getReader();
			while ((line = reader.readLine()) != null) {
				jsonStringBuffer.append(line);
			}
		} catch (Exception e) {
			/* report an error */ }

		String jsonData = jsonStringBuffer.toString();

		// step 3 - schema validation

		try {
			org.json.JSONObject incJsonObj = new org.json.JSONObject(jsonData);

			if (!entitySchema.equals("schema")) {
				// Fetch schema from Redis for validation
				//String schemaJsonStr = jedis.get("schema_" + entitySchema);
				String schemaJsonStr = getEntityOrSchema("schema", entitySchema, request, resp);
				if (schemaJsonStr == null || schemaJsonStr.isEmpty()) {
					resp.setStatus(HttpStatus.BAD_REQUEST.value());
					return "No such schema found";
				}
				System.out.println(schemaJsonStr);

				org.json.JSONObject rawSchema = new org.json.JSONObject(schemaJsonStr);
				removeIds(rawSchema);
				System.out.println("raw schema is:" + rawSchema);
				Schema schemaJson = SchemaLoader.load(rawSchema);

				// validate incoming json input
				schemaJson.validate(incJsonObj);
				System.out.println("validation successful");
			}

			Map<String, Map<String, String>> individualObjects = new HashMap<>();
			Map<String, List<String>> relationships = new HashMap<>();


			String rootObjKey = updateObjectsAndRelationships(incJsonObj, entitySchema, individualObjects,
					relationships);

			for (String key : individualObjects.keySet()) {
				Map<String, String> simpleValMap = individualObjects.get(key);
				jedis.hmset(key, simpleValMap);
			}
			
			// TODO - revisit for lpush review
			for (String key : relationships.keySet()) {
				List<String> simpleValList = relationships.get(key);
				String[] simpleValArr = simpleValList.toArray(new String[simpleValList.size()]);
				jedis.lpush(key, simpleValArr);
			}

			System.out.println("individualObjects" + individualObjects);

			System.out.println("relationships" + relationships);

			String[] keyContents = rootObjKey.split("_");
			
			//Enqueue the document for Elasticsearch indexing
			enqueueForIndexing(entitySchema, request, resp, keyContents[1]);		

			return keyContents[1];

		} catch (JSONException je) {
			je.printStackTrace();
			resp.setStatus(HttpStatus.BAD_REQUEST.value());
			return "Input provided is not a JSON." + je.getMessage();
		} catch (ValidationException ve) {
			ve.printStackTrace();
			resp.setStatus(HttpStatus.BAD_REQUEST.value());
			return "Sorry JSON could not be validated against the schema." + ve.getErrorMessage();
		}

	}

	private void enqueueForIndexing(String entitySchema, HttpServletRequest request, HttpServletResponse resp,
			String id) {
		if(!entitySchema.equals("schema")){
			String savedObjStr = getEntityOrSchema(entitySchema, id, request, resp);
			jedis.lpush("esindexer", savedObjStr);
		}
	}

	private void removeIds(org.json.JSONObject rawSchema) {
		rawSchema.remove("id");
		for(Object key:rawSchema.keySet()){
			Object val = rawSchema.get((String)key);
			if(val instanceof JSONObject){
				removeIds((JSONObject) val);
			} else if(val instanceof JSONArray){
				int len = ((JSONArray) val).length();
				for (int i = 0; i < len; i++) {
					JSONObject item = ((JSONArray) val).getJSONObject(i);
					removeIds(item);
				}
			
			} else if(((String)key).equals("additionalProperties")){
				
				if(((String)val).equals("false")){
					rawSchema.put((String)key, false);
				} else{
					rawSchema.put((String)key, true);
				}
				
			}
		}
	}

	private String updateObjectsAndRelationships(JSONObject incJsonObj, String parentSchema,
			Map<String, Map<String, String>> individualObjects, Map<String, List<String>> relationships) {

		Map<String, String> simpleValues = new HashMap<>();
		UUID uid = UUID.randomUUID();

		String keyForSimpleValues = parentSchema + "_" + uid.toString();
		if(parentSchema.equals("schema")){
			keyForSimpleValues = "schema_" + incJsonObj.getString("title");
		}
		
		for (Object key : incJsonObj.keySet()) {
			Object value = incJsonObj.get(key.toString());
			if (value instanceof String) {
				simpleValues.put(key.toString(), value.toString());
			} else if (value instanceof Boolean) {

				Boolean boolval = (Boolean) value;

				String boolStrRep = boolval.toString();

				simpleValues.put(key.toString(), boolStrRep);

			} else if (value instanceof Integer) {

				simpleValues.put(key.toString(), value.toString());

			} else if (value instanceof Float) {

				simpleValues.put(key.toString(), value.toString());

			} else if (value instanceof JSONObject) {
				String relationKey = keyForSimpleValues + "_" + key.toString();
				List<String> relationshipsList = new ArrayList<>();
				relationships.put(relationKey, relationshipsList);

				// recursively update objects and relationships maps
				relationshipsList.add(updateObjectsAndRelationships((JSONObject) value, key.toString(),
						individualObjects, relationships));

			} else if (value instanceof JSONArray) {

				int len = ((JSONArray) value).length();
				for (int i = 0; i < len; i++) {
					JSONObject item = ((JSONArray) value).getJSONObject(i);
					String relationKey = keyForSimpleValues + "_" + key.toString();
					List<String> relationshipsList = relationships.get(relationKey);
					if (relationshipsList == null) {
						relationshipsList = new ArrayList<>();
						relationships.put(relationKey, relationshipsList);
					}

					// recursively update objects and relationships maps
					relationshipsList
							.add(updateObjectsAndRelationships(item, key.toString(), individualObjects, relationships));
				}
			} else {
				System.out.println("reached else block - Neither String nor JSONObject");
			}
		}

		simpleValues.put("id", uid.toString());
		individualObjects.put(keyForSimpleValues, simpleValues);

		return keyForSimpleValues;
	}

	@RequestMapping("/")
	public String index() {
		return "Greetings from Spring Boot!";
	}

	// step 1 - allow GET request
	@RequestMapping(method = RequestMethod.GET, path = "/{schema}/{id}")
	public String getEntityOrSchema(@PathVariable String schema, @PathVariable String id, HttpServletRequest request,
			HttpServletResponse resp) {

		// Step 2 - Read database

		// fetch simple values of root object from redis using schema and id
		String inputKey = schema + "_" + id;
		Map<String, String> jsonEntity = jedis.hgetAll(inputKey);

		// return HTTP status code 204 for No Content.
		if (jsonEntity == null || jsonEntity.isEmpty()) {
			resp.setStatus(HttpStatus.NO_CONTENT.value());
			return "";
		}

		JSONObject jsonObj = new JSONObject(jsonEntity);

		// get deeply nested JSON object

		appendDeeplyNestedObjects(inputKey, jsonObj);

		// Step 3 - return appropriate response
		String jsonEntityStr = jsonObj.toString();
		// generate Etag
		try {

			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(jsonEntityStr.getBytes());

			byte byteData[] = md.digest();

			// convert the byte to hex format method 1
			StringBuffer sb = new StringBuffer();
			for (int i = 0; i < byteData.length; i++) {
				sb.append(Integer.toString((byteData[i] & 0xff) + 0x100, 16).substring(1));
			}

			String etag = sb.toString();
			resp.setHeader("ETag", etag);

			String reqEtag = request.getHeader("If-None-Match");
			System.out.println("req etag content:  " + reqEtag);
			if (etag.equals(reqEtag)) {
				resp.setStatus(HttpStatus.NOT_MODIFIED.value());
				return "";
			}

		} catch (NoSuchAlgorithmException e) {
			
			e.printStackTrace();
		}

		return jsonEntityStr;
	}

	private void appendDeeplyNestedObjects(String nestedObjKey, JSONObject jsonObj) {
		Set<String> relKeySet = jedis.keys(nestedObjKey + "_*");

		for (String key : relKeySet) {

			Long keyListLen = jedis.llen(key);
			List<String> relChildKeys = jedis.lrange(key, 0, keyListLen);

			if (relChildKeys.size() > 1) {
				JSONArray arr = new JSONArray();
				for (String childKey : relChildKeys) {
					Map<String, String> childMap = jedis.hgetAll(childKey);
//					if(schemaFlag){
//						childMap.remove("id");
//					}
					
					JSONObject innerObj = new JSONObject(childMap);
					appendDeeplyNestedObjects(childKey, innerObj);
					arr.put(innerObj);
				}
				String[] keyContents = relChildKeys.get(0).split("_");
				jsonObj.put(keyContents[0], arr);
			} else {
				String innerObjKey = relChildKeys.get(0);
				Map<String, String> childMap = jedis.hgetAll(innerObjKey);
//				if(schemaFlag){
//					childMap.remove("id");
//				}				
				String[] keyContents = innerObjKey.split("_");
				JSONObject innerObj = new JSONObject(childMap);
				jsonObj.put(keyContents[0], innerObj);
				appendDeeplyNestedObjects(innerObjKey, innerObj);
			}
		}

	}

	@RequestMapping(method = RequestMethod.DELETE, path = "/{entitySchema}/{id}")
	public String deleteSchemaOrEntity(@PathVariable String entitySchema, @PathVariable String id, HttpServletRequest request, HttpServletResponse resp){
		String inputKey = entitySchema + "_" + id;
		Long delStat = jedis.del(inputKey);
		if(delStat>0){
			Set<String> relKeySet = jedis.keys(inputKey + "_*");
			System.out.println("relKeySet: " + relKeySet);
			for(String indKey:relKeySet){
				Long keyListLen = jedis.llen(indKey);
				List<String> relChildKeys = jedis.lrange(indKey, 0, keyListLen);
				for(String childKey : relChildKeys){
					jedis.del(childKey);
				}
				jedis.del(indKey);
			}
			return "Deleted";
		}else{
			resp.setStatus(HttpStatus.NO_CONTENT.value());
			return "Document not found!";
		}
		
	}
	
	@RequestMapping(method = RequestMethod.PUT, consumes = "application/json", path = "/{entitySchema}/{id}")
	public String putEntityOrSchema(@PathVariable String entitySchema, @PathVariable String id, HttpServletRequest request, HttpServletResponse resp) {
		String inputKey = entitySchema + "_" + id;
		deleteSchemaOrEntity(entitySchema,id,request,resp);
		String newObjId = createEntityOrSchema(entitySchema, request, resp);
		if(resp.getStatus()==400){
			return newObjId;
		}
		String newInputKey = entitySchema + "_" + newObjId;
		Map<String, String> rootSimpleValMap = jedis.hgetAll(newInputKey);
		
		//Don't do for schema
		if(!entitySchema.equals("schema")){
			jedis.del(newInputKey);
			rootSimpleValMap.put("id", id);
		}		
		jedis.hmset(inputKey, rootSimpleValMap);
		
		if(!entitySchema.equals("schema")){
			//Retrieve rel keys from new input key
			Set<String> relKeyList = jedis.keys(newInputKey + "_*");
			
			//Iterate on rel keys to fetch corresponding object keys
			for(String relKey : relKeyList){
				Long listLen = jedis.llen(relKey);
				String[] strArr = relKey.split("_");
				int arrLen = strArr.length;
			
				//Form new rel keys using existing id
				String newRelKey = inputKey + "_" + strArr[arrLen-1];
				List<String> innerKeyList = jedis.lrange(relKey, 0, listLen);
				
				//Copy the inner list of keys to the newly created rel key
				jedis.lpush(newRelKey, innerKeyList.toArray(new String[innerKeyList.size()]));
			}
		}		
		enqueueForIndexing(entitySchema, request, resp, id);
		return id;
	}
	
	@RequestMapping(method = RequestMethod.PATCH, consumes = "application/json", path = "/{entitySchema}")
	public String mergeEntityOrSchema(@PathVariable String entitySchema, HttpServletRequest request, HttpServletResponse resp) {
		// step 2 - read json and parse it using json simple

		StringBuffer jsonStringBuffer = new StringBuffer();
		String line = null;
		try {
			BufferedReader reader = request.getReader();
			while ((line = reader.readLine()) != null) {
				jsonStringBuffer.append(line);
			}

			String jsonData = jsonStringBuffer.toString();
			org.json.JSONObject incJsonObj = new org.json.JSONObject(jsonData);
			validateSchemaForEntities(entitySchema, incJsonObj, request, resp);			
			
			String objId;
			if(entitySchema.equals("schema")){
				objId = incJsonObj.getString("title");
			}else{
				objId = incJsonObj.getString("id");
			}
			mergeIndividualObjects(incJsonObj, entitySchema, objId);
			enqueueForIndexing(entitySchema, request, resp, objId);
			return "merge successful";
		} catch (ValidationException ve) {
			resp.setStatus(HttpStatus.BAD_REQUEST.value());
			return "Sorry JSON could not be validated against the schema." + ve.getErrorMessage();
		} catch (Exception e) {
			resp.setStatus(HttpStatus.BAD_REQUEST.value());
			e.printStackTrace();
			return "Unexpected error";
		}

	}

	private void validateSchemaForEntities(String entitySchema, org.json.JSONObject incJsonObj, HttpServletRequest request, HttpServletResponse resp) {
		if(!entitySchema.equals("schema")){
			// Fetch schema from Redis for validation
			String schemaJsonStr = getEntityOrSchema(entitySchema, incJsonObj.getString("id"), request, resp);
			org.json.JSONObject rawSchema = new org.json.JSONObject(schemaJsonStr);
			Schema schemaJson = SchemaLoader.load(rawSchema);

			// validate incoming json input
			schemaJson.validate(incJsonObj);
		}
	}

	private void mergeIndividualObjects(JSONObject incJsonObj, String entitySchema, String objId) {

		String rootObjKey = entitySchema + "_" + objId;

		for (Object keyObj : incJsonObj.keySet()) {
			String field = keyObj.toString();
			Object value = incJsonObj.get(field);
			if (value instanceof String || value instanceof Integer || value instanceof Float
					|| value instanceof Boolean) {
				jedis.hset(rootObjKey, field, value.toString());
			} else if (value instanceof JSONObject) {
				checkIdAndRecursiveMerge(field, value, rootObjKey);
			} else if (value instanceof JSONArray) {
				JSONArray valueArr = (JSONArray) value;
				int len = valueArr.length();
				for (int i = 0; i < len; i++) {
					//String innerObjId = ((JSONObject) valueArr.get(i)).getString("id");
					//mergeIndividualObjects((JSONObject) valueArr.get(i), field, innerObjId);
					checkIdAndRecursiveMerge(field, valueArr.get(i), rootObjKey);
					
				}
			}

		}

	}

	private void checkIdAndRecursiveMerge(String field, Object value, String rootObjKey) {
		
		try{
			Object innerObjId = ((JSONObject) value).get("id");
			String innerObjIdStr = (String)innerObjId;
			mergeIndividualObjects((JSONObject) value, field, innerObjIdStr);
		}catch(JSONException je){
			//No ID found for new object. Create new ID and save object
			UUID newUid = UUID.randomUUID();
			//Form relationship key
			String relKey = rootObjKey + "_" + field;
			//Form relationship value
			String relVal = field + "_" + newUid;
			//Save relationship in redis
			jedis.lpush(relKey, relVal);
			((JSONObject) value).put("id", newUid.toString());
			mergeIndividualObjects((JSONObject)value, field, newUid.toString());
		}
	}

}
