/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.alibaba.rocketmq.tools.command.stats;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import com.alibaba.rocketmq.client.exception.MQBrokerException;
import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.UtilAll;
import com.alibaba.rocketmq.common.protocol.body.BrokerStatsData;
import com.alibaba.rocketmq.common.protocol.body.GroupList;
import com.alibaba.rocketmq.common.protocol.body.TopicList;
import com.alibaba.rocketmq.common.protocol.route.BrokerData;
import com.alibaba.rocketmq.common.protocol.route.TopicRouteData;
import com.alibaba.rocketmq.remoting.RPCHook;
import com.alibaba.rocketmq.remoting.exception.RemotingException;
import com.alibaba.rocketmq.store.stats.BrokerStatsManager;
import com.alibaba.rocketmq.tools.admin.DefaultMQAdminExt;
import com.alibaba.rocketmq.tools.command.MQAdminStartup;
import com.alibaba.rocketmq.tools.command.SubCommand;

//sh mqadmin statsAll -n xxx
public class StatsAllSubCommand implements SubCommand {

    @Override
    public String commandName() {
        return "statsAll";
    }


    @Override
    public String commandDesc() {
        return "Topic and Consumer tps stats";
    }


    @Override
    public Options buildCommandlineOptions(Options options) {
        Option opt = new Option("a", "activeTopic", false, "print active topic only");
        opt.setRequired(false);
        options.addOption(opt);

        return options;
    }


    public static long compute24HourSum(BrokerStatsData bsd) {
        if (bsd.getStatsDay().getSum() != 0) {
            return bsd.getStatsDay().getSum();
        }

        if (bsd.getStatsHour().getSum() != 0) {
            return bsd.getStatsHour().getSum();
        }

        if (bsd.getStatsMinute().getSum() != 0) {
            return bsd.getStatsMinute().getSum();
        }

        return 0;
    }

    //一个topic一个topic的获取信息，在execute进行汇总
    public static void printTopicDetail(final DefaultMQAdminExt admin, final String topic, final boolean activeTopic)
            throws RemotingException, MQClientException, InterruptedException, MQBrokerException {
        TopicRouteData topicRouteData = admin.examineTopicRouteInfo(topic);

        //获取topic的所有consumer消费者分组信息和所有的offset信息.,见AdminBrokerProcessor.processRequest.queryTopicConsumeByWho
        GroupList groupList = admin.queryTopicConsumeByWho(topic);
        double inTPS = 0;

        long inMsgCntToday = 0;

        //整个topic的intps(intps和消费分组无关，只有outpts才和具体的消费者分组有关)
        for (BrokerData bd : topicRouteData.getBrokerDatas()) {
            String masterAddr = bd.getBrokerAddrs().get(MixAll.MASTER_ID);
            if (masterAddr != null) {
                try {
                    //sh mqadmin statsAll -获取 #InTPS     #OutTPS   #InMsg24Hour  #OutMsg24Hour信息
                    BrokerStatsData bsd = admin.ViewBrokerStatsData(masterAddr, BrokerStatsManager.TOPIC_PUT_NUMS, topic);
                    inTPS += bsd.getStatsMinute().getTps();
                    inMsgCntToday += compute24HourSum(bsd);
                }
                catch (Exception e) {
                }
            }
        }

        //topic下每个消费者分组对应的out tps
        if (groupList != null && !groupList.getGroupList().isEmpty()) {
            for (String group : groupList.getGroupList()) {
                double outTPS = 0;
                long outMsgCntToday = 0;

                for (BrokerData bd : topicRouteData.getBrokerDatas()) {
                    String masterAddr = bd.getBrokerAddrs().get(MixAll.MASTER_ID);
                    if (masterAddr != null) {
                        try {
                            String statsKey = String.format("%s@%s", topic, group);
                            BrokerStatsData bsd = admin.ViewBrokerStatsData(masterAddr, BrokerStatsManager.GROUP_GET_NUMS, statsKey);
                            outTPS += bsd.getStatsMinute().getTps();
                            outMsgCntToday += compute24HourSum(bsd);
                        }
                        catch (Exception e) {

                        }
                    }
                }

                if (!activeTopic || (inMsgCntToday > 0) || //
                        (outMsgCntToday > 0)) {
                    System.out.printf("%-32s  %-32s %11.2f %11.2f %14d %14d\n",//
                        UtilAll.frontStringAtLeast(topic, 32),//
                        UtilAll.frontStringAtLeast(group, 32),//
                        inTPS,//
                        outTPS,//
                        inMsgCntToday,//
                        outMsgCntToday//
                        );
                }
            }
        }
        else {
            if (!activeTopic || (inMsgCntToday > 0)) {
                System.out.printf("%-32s  %-32s %11.2f %11s %14d %14s\n",//
                    UtilAll.frontStringAtLeast(topic, 32),//
                    "",//
                    inTPS,//
                    "",//
                    inMsgCntToday,//
                    "NO_CONSUMER"//
                );
            }
        }
    }


    @Override
    public void execute(CommandLine commandLine, Options options, RPCHook rpcHook) {
        DefaultMQAdminExt defaultMQAdminExt = new DefaultMQAdminExt(rpcHook);

        defaultMQAdminExt.setInstanceName(Long.toString(System.currentTimeMillis()));

        try {
            defaultMQAdminExt.start();

            TopicList topicList = defaultMQAdminExt.fetchAllTopicList();

            System.out.printf("%-32s  %-32s %11s %11s %14s %14s\n",//
                "#Topic",//
                "#Consumer Group",//
                "#InTPS",//
                "#OutTPS",//
                "#InMsg24Hour",//
                "#OutMsg24Hour"//
            );

            boolean activeTopic = commandLine.hasOption('a'); //加上-a参数则只获取活跃的消费分组，并打印

            for (String topic : topicList.getTopicList()) {
                if (topic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX) || topic.startsWith(MixAll.DLQ_GROUP_TOPIC_PREFIX)) {
                    continue;
                }

                try {
                    printTopicDetail(defaultMQAdminExt, topic, activeTopic);
                }
                catch (Exception e) {
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            defaultMQAdminExt.shutdown();
        }
    }


    public static void main(String[] args) {
        System.setProperty(MixAll.NAMESRV_ADDR_PROPERTY, "10.101.87.102:9876");
        MQAdminStartup.main(new String[] { new StatsAllSubCommand().commandName() });
    }
}
