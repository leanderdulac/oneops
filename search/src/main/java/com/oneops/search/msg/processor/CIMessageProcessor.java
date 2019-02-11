/*******************************************************************************
 *
 *   Copyright 2015 Walmart, Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *******************************************************************************/
package com.oneops.search.msg.processor;

import static org.elasticsearch.index.query.QueryBuilders.queryStringQuery;

import com.oneops.cms.cm.domain.CmsCI;
import com.oneops.cms.simple.domain.CmsCISimple;
import com.oneops.cms.simple.domain.CmsCISimpleWithTags;
import com.oneops.cms.simple.domain.CmsWorkOrderSimple;
import com.oneops.cms.util.CmsUtil;
import com.oneops.search.domain.CmsCISearch;
import com.oneops.search.msg.index.Indexer;
import com.oneops.search.msg.processor.ci.DeploymentPlanProcessor;
import com.oneops.search.msg.processor.ci.PolicyProcessor;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.search.sort.SortOrder;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CIMessageProcessor implements MessageProcessor {
    private static Logger logger = Logger.getLogger(CIMessageProcessor.class);
    public  static final String ENV_SUCCESS_PREFIX = "SUCCESS: Generation time taken: ";
    private static final int RETRY_COUNT = 5;
    private static final long TIME_TO_WAIT = 5000;
    

    private Client client;
    private Indexer indexer;
    private PolicyProcessor policyProcessor;
    private DeploymentPlanProcessor deploymentPlanProcessor;
    private CmsUtil cmsUtil;

    @Autowired
    public void setClient(Client client) {
        this.client = client;
    }

    @Autowired
    public void setIndexer(Indexer indexer) {
        this.indexer = indexer;
    }

    @Autowired
    public void setPolicyProcessor(PolicyProcessor policyProcessor) {
        this.policyProcessor = policyProcessor;
    }

    @Autowired
    public void setDeploymentPlanProcessor(DeploymentPlanProcessor deploymentPlanProcessor) {
        this.deploymentPlanProcessor = deploymentPlanProcessor;
    }


    @Autowired
    public void setCmsUtil(CmsUtil cmsUtil) {
        this.cmsUtil = cmsUtil;
    }

    @Override
    public void processMessage(String message, String msgType, String msgId) {
        CmsCISimple simpleCI = null;
        if ("cm_ci_new".equals(msgType)) {
            simpleCI = GSON.fromJson(message, CmsCISimpleWithTags.class);
        } else {
            CmsCI ci = GSON.fromJson(message, CmsCI.class);
            simpleCI = cmsUtil.custCI2CISimple(ci, "df");
        }
        indexer.indexEvent("ci", GSON_ES.toJson(simpleCI));
        //For plan generation metrics
        if ("manifest.Environment".equals(simpleCI.getCiClassName()) && StringUtils.isNotEmpty(simpleCI.getComments()) &&
            simpleCI.getComments().startsWith(ENV_SUCCESS_PREFIX)) {
            deploymentPlanProcessor.process(simpleCI);
        } else if ("account.Policy".equals(simpleCI.getCiClassName()) || "mgmt.manifest.Policy".equals(simpleCI.getCiClassName())) {
            policyProcessor.process(simpleCI);
        }


        //add wo to all bom cis
        if (simpleCI.getCiClassName().startsWith("bom")) {
            try {
                message = this.processBomCI(simpleCI);
            } catch (Exception e){
                message = GSON_ES.toJson(simpleCI);
            }
        } else {
            message = GSON_ES.toJson(simpleCI);
        }

        try {
            indexer.index(String.valueOf(simpleCI.getCiId()), "ci", message);
        } catch (Exception e) {
            logger.error("There was an error indexing message first time", e);
            // try one more time
            indexer.index(String.valueOf(simpleCI.getCiId()), "ci", message);
        }
    }




  
    /**
     * @param ci
     * @return
     */
    private String processBomCI(CmsCISimple ci) {
        CmsCISearch ciSearch = new CmsCISearch();
        BeanUtils.copyProperties(ci, ciSearch);
        long ciId = ciSearch.getCiId();
        CmsWorkOrderSimple wos = null;
        for (int i = 0; i < RETRY_COUNT; i++) {
            try {
                SearchResponse response = client.prepareSearch("cms-2*")
                        .setTypes("workorder")
                        .setQuery(queryStringQuery("rfcCi.ciId:"+ciId+" AND dpmtRecordState:complete"))
                        .addSort("searchTags.responseDequeTS", SortOrder.DESC)
                        .setSize(1)
                        .execute()
                        .actionGet();

                if (response.getHits().getHits().length > 0) {
                    String cmsWo = response.getHits().getHits()[0].getSourceAsString();
                    wos = GSON.fromJson(cmsWo, CmsWorkOrderSimple.class);
                    logger.info("WO found for ci id " + ciId + " on retry " + i);
                    wos.payLoad.remove("RequiresComputes");
                    wos.payLoad.remove("SecuredBy");
                    wos.payLoad.remove("DependsOn");
                    applyDateFixes(wos);
                    ciSearch.setWorkorder(wos);
                    break;
                } else {
                    Thread.sleep(TIME_TO_WAIT); //wait for TIME_TO_WAIT ms and retry
                }
            } catch (Exception e) {
                logger.error("Error in retrieving WO for ci " + ciId);
                e.printStackTrace();
            }
        }
        try {
            if (wos == null) {
                logger.info("WO not found for ci " + ci.getCiId() + " of type " + ci.getCiClassName());
                GetResponse response = client.prepareGet(indexer.getIndexName(), "ci", "" + ci.getCiId()).get();
                if (response.isExists()) {
                    Object workorder = response.getSource().get("workorder");
                    if (workorder!=null) {
                        wos = GSON_ES.fromJson(GSON.toJson(workorder), CmsWorkOrderSimple.class);
                        ciSearch.setWorkorder(wos);
                    }
                }
            }
        } catch (Exception e){
            logger.error("Error fetching WO from CI", e);
        }
        return GSON_ES.toJson(ciSearch);
    }


    /**
     * This code is to handle expires_on field defined as date in ES that is currently coming in a very weird ioformat.
     * Until mapping is adjusted we need to translate it to one of the date formats known to ES or CI won't index.
     * @param wos
     */
    private void applyDateFixes(CmsWorkOrderSimple wos) {
        if (wos.getRfcCi()!=null) {
            convertIllegalDateFormat(wos.getRfcCi().getCiAttributes(), "expires_on");
        }
        if (wos.getResultCi()!=null) {
            convertIllegalDateFormat(wos.getResultCi().getCiAttributes(),"expires_on");
        }
    }

    private static void convertIllegalDateFormat(Map<String, String> ciAttributes, String name) {
        if (ciAttributes!=null && ciAttributes.containsKey(name)){
            String date = ciAttributes.get(name);

            //Nov  5 21:08:38 2019 GMT
            //Jan 22 18:21:47 2020 GMT
            // Some of the `expires_one` date fields apparently have date format with two spaces, so while this conversion is a hack,
            // until we migrate to newer ES version and change the mapping we need to accommodate both, since single DateTimeFormat can't handle it.
            DateTimeFormatter wrongFormat = DateTimeFormat.forPattern("MMM dd HH:mm:ss yyyy z");
            DateTimeFormatter wrongFormat2 = DateTimeFormat.forPattern("MMM  dd HH:mm:ss yyyy z");

            /// 2020-01-22T18:21:47
            DateTimeFormatter rightFormat = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss");
            try {
                ciAttributes.put(name, rightFormat.print(wrongFormat.parseMillis(date)));
            } catch (Exception e) {
                try {
                    ciAttributes.put(name, rightFormat.print(wrongFormat2.parseMillis(date)));
                } catch (Exception ignore) {
                    // do nothing, unexpected date format
                }
            }
        }
    }

    public static void main(String[] args) {
        Map<String, String> map = new HashMap<>();
        map.put("test", "Nov 5 21:08:38 2019 GMT");
        map.put("test1", "Nov  5 21:08:38 2019 GMT");

        convertIllegalDateFormat(map, "test");
        convertIllegalDateFormat(map, "test1");
        System.out.println(map);
        System.out.println(ISODateTimeFormat.dateOptionalTimeParser().parseDateTime(map.get("test")));
        System.out.println(ISODateTimeFormat.dateOptionalTimeParser().parseDateTime(map.get("test1")));

    }

}
