package com.hmdp.ai.domain.external;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public interface ExternalSearchGateway {List<SearchResult> search(String query,int limit,JsonNode configuration);}
