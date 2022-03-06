package io.diveni.backend.service.projectmanagementproviders.jiracloud;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.api.client.util.Base64;

import io.diveni.backend.Utils;
import io.diveni.backend.model.Project;
import io.diveni.backend.model.TokenIdentifier;
import io.diveni.backend.model.UserStory;
import io.diveni.backend.service.projectmanagementproviders.ProjectManagementProviderOAuth2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import io.diveni.backend.controller.ErrorMessages;
import lombok.Getter;
import lombok.val;

@Service
public class JiraCloudService implements ProjectManagementProviderOAuth2 {
    @Value("${JIRA_CLOUD_CLIENTID}")
    private String CLIENT_ID;
    @Value("${JIRA_CLOUD_CLIENTSECRET}")
    private String CLIENT_SECRET;
    @Value("${JIRA_CLOUD_ESTIMATIONFIELD:customfield_10016}")
    private String ESTIMATION_FIELD;

    private final int JIRA_CLOUD_API_VERSION = 2;
    private final String JIRA_OAUTH_URL = "https://auth.atlassian.com/oauth";
    private final String JIRA_HOME = "https://api.atlassian.com/ex/jira/%s/rest/api/";

    @Getter
    private final Map<String, String> accessTokens = new HashMap<>();

    @Override
    public TokenIdentifier getAccessToken(String authorizationCode, String origin) {
        RestTemplate restTemplate = new RestTemplate();
        String credentials = CLIENT_ID + ":" + CLIENT_SECRET;
        String encodedCredentials = new String(Base64.encodeBase64(credentials.getBytes()));

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        headers.add("Authorization", "Basic " + encodedCredentials);

        HttpEntity<String> request = new HttpEntity<String>(headers);

        String accessTokenURL = JIRA_OAUTH_URL + "/token";
        accessTokenURL += "?code=" + authorizationCode;
        accessTokenURL += "&grant_type=authorization_code";
        accessTokenURL += "&redirect_uri=" + origin + "/#/jiraCallback";

        ResponseEntity<String> response = restTemplate.exchange(accessTokenURL, HttpMethod.POST, request, String.class);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode node;
        try {
            node = mapper.readTree(response.getBody());
            String accessToken = node.path("access_token").asText();
            val id = Utils.generateRandomID();
            accessTokens.put(id, accessToken);
            return new TokenIdentifier(id);
        } catch (Exception e) {
            e.printStackTrace();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorMessages.failedToRetrieveAccessTokenErrorMessage);
        }
    }

    @Override
    public List<Project> getProjects(String tokenIdentifier) {
        val accessToken = accessTokens.get(tokenIdentifier);
        String cloudID = getCloudID(accessToken);
        try {
            List<Project> projects = new ArrayList<>();
            ResponseEntity<String> response = executeRequest(String.format(getJiraUrl(), cloudID) + "/project/search",
                    HttpMethod.GET, accessToken, null);
            JsonNode node = new ObjectMapper().readTree(response.getBody());

            for (JsonNode projectNode : node.path("values")) {
                projects.add(new Project(projectNode.get("name").asText(), projectNode.get("id").asText()));
            }
            return projects;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorMessages.failedToRetrieveProjectsErrorMessage);
        }
    }

    @Override
    public List<UserStory> getIssues(String tokenIdentifier, String projectName) {
        String cloudID = getCloudID(accessTokens.get(tokenIdentifier));
        ResponseEntity<String> response = executeRequest(
                String.format(
                        getJiraUrl(), cloudID) + "/search?jql=project='" + projectName
                        + "' order by rank&fields=summary,description," + ESTIMATION_FIELD,
                HttpMethod.GET, accessTokens.get(tokenIdentifier), null);
        try {
            List<UserStory> userStories = new ArrayList<>();
            JsonNode node = new ObjectMapper().readTree(response.getBody());
            for (JsonNode issue : node.path("issues")) {
                val fields = issue.get("fields");
                String estimation = fields.get(ESTIMATION_FIELD).isNull() ? null
                        : String.valueOf(fields.get(ESTIMATION_FIELD).asDouble());
                if (estimation != null && estimation.endsWith(".0")) {
                    estimation = estimation.substring(0, estimation.length() - 2);
                }
                userStories.add(new UserStory(issue.get("id").textValue(), fields.get("summary").textValue(),
                        fields.get("description").textValue(), estimation, false));
            }
            return userStories;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorMessages.failedToRetrieveProjectsErrorMessage);
        }
    }

    @Override
    public void updateIssue(String tokenIdentifier, UserStory story) {
        String cloudID = getCloudID(accessTokens.get(tokenIdentifier));
        Map<String, Map<String, Object>> content = new HashMap<>();
        Map<String, Object> fields = new HashMap<>();
        fields.put("summary", story.getTitle());
        fields.put("description", story.getDescription());
        if (story.getEstimation() != null) {
            try {
                fields.put(ESTIMATION_FIELD, Double.parseDouble(story.getEstimation()));
            } catch (NumberFormatException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        ErrorMessages.failedToEditIssueErrorMessage);
            }
        }
        content.put("fields", fields);
        try {
            executeRequest(String.format(getJiraUrl(), cloudID) + "/issue/" + story.getJiraId(), HttpMethod.PUT,
                    accessTokens.get(tokenIdentifier), content);
        } catch (Exception e) {
            e.printStackTrace();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorMessages.failedToEditIssueErrorMessage);
        }

    }

    @Override
    public void deleteIssue(String tokenIdentifier, String jiraID) {
        try {
            String cloudID = getCloudID(accessTokens.get(tokenIdentifier));
            executeRequest(String.format(getJiraUrl(), cloudID) + "/issue/" + jiraID, HttpMethod.DELETE,
                    accessTokens.get(tokenIdentifier), null);
        } catch (Exception e) {
            e.printStackTrace();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorMessages.failedToEditIssueErrorMessage);
        }
    }

    @Override
    public String getCurrentUsername(String tokenIdentifier) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean containsToken(String token) {
        return accessTokens.containsKey(token);
    }

    static String getCloudID(String accessToken) {
        String accessibleResourcesURL = "https://api.atlassian.com/oauth/token/accessible-resources";
        ResponseEntity<String> response = executeRequest(accessibleResourcesURL, HttpMethod.GET, accessToken, null);
        try {
            ObjectNode[] node = new ObjectMapper().readValue(response.getBody(), ObjectNode[].class);
            for (ObjectNode objectNode : node) {
                if (objectNode.has("id")) {
                    return objectNode.get("id").asText();
                }
            }
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    ErrorMessages.failedToRetrieveAccessTokenErrorMessage);
        }
    }

    static ResponseEntity<String> executeRequest(String url, HttpMethod method, String accessToken, Object body) {
        RestTemplate restTemplate = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        headers.add("Authorization", "Bearer " + accessToken);
        HttpEntity<Object> request = new HttpEntity<Object>(body, headers);
        return restTemplate.exchange(url, method, request, String.class);
    }

    @Override
    public String createIssue(String tokenIdentifier, String projectID, UserStory story) {
        // TODO Auto-generated method stub
        return null;
    }

    private String getJiraUrl() {
        return JIRA_HOME + JIRA_CLOUD_API_VERSION;
    }

}