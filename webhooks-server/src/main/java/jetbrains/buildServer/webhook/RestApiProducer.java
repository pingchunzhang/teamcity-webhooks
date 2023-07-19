package jetbrains.buildServer.webhook;

import jetbrains.buildServer.web.impl.RestApiFacade;
import jetbrains.buildServer.webhook.async.events.AsyncEvent;

import org.eclipse.egit.github.core.event.PublicPayload;
import org.springframework.stereotype.Component;

import com.google.gson.JsonArray;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import com.intellij.openapi.diagnostic.Logger;

//import org.json.JSONArray;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static java.lang.String.format;
import static jetbrains.buildServer.webhook.WebhooksManager.EventNames.*;
import static jetbrains.buildServer.webhook.WebhooksManager.EventNames.BUILD_INTERRUPTED;

/**
 * {@link WebhookDataProducer} uses TeamCity rest-api for building webhooks payload
 */
@Component
public class RestApiProducer implements WebhookDataProducer {

    private static final Logger LOG = Logger.getInstance(RestApiProducer.class.getName());
    private enum EventType {
        AGENT(Arrays.asList(AGENT_REGISTRED, AGENT_UNREGISTERED, AGENT_REMOVED), "/app/rest/agents/id:"),
        BUILD(Arrays.asList(BUILD_STARTED, BUILD_FINISHED, BUILD_INTERRUPTED, CHANGES_LOADED, BUILD_TYPE_ADDED_TO_QUEUE, BUILD_REMOVED_FROM_QUEUE, BUILD_PROBLEMS_CHANGED), "/app/rest/builds/promotionId:");

        private String restApiUrl;
        private List<String> events;

        EventType(List<String> events, String restApiUrl) {
            this.events = events;
            this.restApiUrl = restApiUrl;
        }

        static EventType getEventType(String event) {
            for(EventType type : EventType.values()) {
                if (type.events.contains(event))
                    return type;
            }
            throw new IllegalArgumentException(format("Event %s is not supported.", event));
        }

        String getRestApiUrl() {
            return restApiUrl;
        }
    }

    private final RestApiFacade restApiFacade;

    public RestApiProducer(RestApiFacade restApiFacade) {
        this.restApiFacade = restApiFacade;
    }

    @Override
    public String getJson(AsyncEvent event, String fields, String softwareType) {
        EventType eventType = EventType.getEventType(event.getEventType());
        String objectRestUrl = eventType.getRestApiUrl() + event.getObjectId();
        try {
            String catchPayload = restApiFacade.getJson(objectRestUrl, fields);
            String catchEvent = event.getEventType();
            switch(softwareType){
                case "feishu" :
                    //return format("{ \"msg_type\" : \"post\", \"content\" : {\"post\" : {\"zh_cn\": \"title\": \"%s\"}}}", catchEvent);
                    //break;
                    LOG.warn("start get feishu json ");
                    return getFeishuJson(catchEvent, catchPayload);
                default :
                    return format("{ \"eventType\" : \"%s\", \"payload\" : %s }", catchEvent, catchPayload);
            }

            //return format("{ \"eventType\" : \"%s\", \"payload\" : %s }", event.getEventType(), restApiFacade.getJson(objectRestUrl, fields));
        } catch (RestApiFacade.InternalRestApiCallException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean support(AsyncEvent event) {
        try {
            EventType.getEventType(event.getEventType());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return true;
    }

    private String getFeishuJson(String eventType, String json) {
        //JSONParser parser = new JSONParser();
        //JSONObject res = (JSONObject) parser.parse(json);
        //Iterator j = res.iterator();

        JSONObject feishuJson = new JSONObject();
        try {
            JSONParser jParser = new JSONParser();
            JSONObject jObject = (JSONObject) jParser.parse(json);

            JSONArray feishuContent = new JSONArray();
            JSONArray feishuContent_tmp = new JSONArray();

            if (!eventType.equals("BUILD_STARTED")){
                // If the build succeed, the statusText is 'Success'.
                // Otherwise, statusText contains the reason why the build failed.
                // For example:
                //    COREDUMP Tests failed: 160 (142 new), passed: 260
                String status = (String) jObject.getOrDefault("statusText", "success");
                JSONObject feishuStatus = new JSONObject();
                feishuStatus.put("tag", "text");
                feishuStatus.put("text", "status: " + status + '\n');

                feishuContent_tmp.add(feishuStatus);
            }

            String build_id = (String) jObject.get("buildTypeId");
            JSONObject feishuBuild = new JSONObject();
            feishuBuild.put("tag", "text");
            feishuBuild.put("text", "project: " + build_id + "\n");

            JSONObject trigger = (JSONObject) jObject.get("triggered");
            JSONObject user = (JSONObject)trigger.get("user");
            String triggerman = (String) user.get("username");
            JSONObject feishuTriggerman = new JSONObject();
            feishuTriggerman.put("tag", "text");
            feishuTriggerman.put("text", "triggerman: " + triggerman + '\n');

            JSONObject feishuUrlkey = new JSONObject();
            feishuUrlkey.put("tag", "text");
            feishuUrlkey.put("text", "U can click: ");

            String weburl = (String) jObject.get("webUrl");
            JSONObject tcUrl = new JSONObject();
            tcUrl.put("tag", "a");
            tcUrl.put("text", "teamcity url \n");
            tcUrl.put("href", weburl);

            feishuContent_tmp.add(feishuBuild);
            feishuContent_tmp.add(feishuUrlkey);
            feishuContent_tmp.add(tcUrl);
            feishuContent_tmp.add(feishuTriggerman);
            feishuContent.add(feishuContent_tmp);

            JSONObject feishuZhcontent = new JSONObject();
            feishuZhcontent.put("title", eventType);
            feishuZhcontent.put("content", feishuContent);

            JSONObject feishuZh = new JSONObject();
            feishuZh.put("zh_cn", feishuZhcontent);

            JSONObject feishuPost = new JSONObject();
            feishuPost.put("post", feishuZh);

            feishuJson.put("msg_type", "post");
            feishuJson.put("content", feishuPost);

        } catch (Exception ex) {
            LOG.warn("feishu json inner " + json);
            LOG.warn("exception: " + ex.getMessage());
            ex.printStackTrace();
        }
        LOG.warn("feishu json parser done");
        //JSONArray feishuArray = new JSONArray();
        return feishuJson.toString();
    }
}
