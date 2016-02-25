/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.rule.ws;

import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import org.sonar.api.resources.Language;
import org.sonar.api.resources.Languages;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.db.DbSession;
import org.sonar.db.qualityprofile.ActiveRuleDto;
import org.sonar.db.qualityprofile.ActiveRuleKey;
import org.sonar.db.qualityprofile.ActiveRuleParamDto;
import org.sonar.db.qualityprofile.QualityProfileDto;
import org.sonar.db.rule.RuleDto;
import org.sonar.server.db.DbClient;
import org.sonar.server.qualityprofile.ActiveRule;
import org.sonar.server.qualityprofile.QProfileLoader;
import org.sonar.server.rule.Rule;
import org.sonar.server.rule.index.RuleQuery;
import org.sonarqube.ws.Rules;
import org.sonarqube.ws.Rules.SearchResponse;
import org.sonarqube.ws.Rules.ShowResponse;

import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.Sets.newHashSet;
import static java.util.Collections.singletonList;
import static org.sonar.server.ws.WsUtils.checkFoundWithOptional;

/**
 * Add details about active rules to api/rules/search and api/rules/show
 * web services.
 */
@ServerSide
public class ActiveRuleCompleter {

  private static final Logger LOG = Loggers.get(ActiveRuleCompleter.class);

  private final DbClient dbClient;
  private final QProfileLoader loader;
  private final Languages languages;

  public ActiveRuleCompleter(DbClient dbClient, QProfileLoader loader, Languages languages) {
    this.dbClient = dbClient;
    this.loader = loader;
    this.languages = languages;
  }

  void completeSearch(DbSession dbSession, RuleQuery query, List<RuleDto> rules, SearchResponse.Builder searchResponse) {
    Collection<String> harvestedProfileKeys = writeActiveRules(dbSession, searchResponse, query, rules);
    searchResponse.setQProfiles(buildQProfiles(harvestedProfileKeys));
  }

  private Collection<String> writeActiveRules(DbSession dbSession, SearchResponse.Builder response, RuleQuery query, Collection<RuleDto> rules) {
    Collection<String> qProfileKeys = newHashSet();
    Rules.Actives.Builder activesBuilder = response.getActivesBuilder();

    String profileKey = query.getQProfileKey();
    if (profileKey != null) {
      // Load details of active rules on the selected profile
      for (RuleDto rule : rules) {
        ActiveRule activeRule = loader.getActiveRule(ActiveRuleKey.of(profileKey, rule.getKey()));
        if (activeRule != null) {
          Optional<ActiveRuleDto> activeRuleDto = dbClient.activeRuleDao().selectByActiveRuleKey(dbSession, activeRule.key());
          checkFoundWithOptional(activeRuleDto, "Active rule with key '%s' not found", activeRule.key().toString());
          List<ActiveRuleParamDto> activeRuleParamDtos = dbClient.activeRuleDao().selectParamsByActiveRuleId(dbSession, activeRuleDto.get().getId());
          ListMultimap<ActiveRuleKey, ActiveRuleParamDto> activeRuleParamByActiveRuleKey = ArrayListMultimap.create(1, activeRuleParamDtos.size());
          activeRuleParamByActiveRuleKey.putAll(activeRule.key(), activeRuleParamDtos);
          qProfileKeys = writeActiveRules(rule.getKey(), singletonList(activeRule), activeRuleParamByActiveRuleKey, activesBuilder);
        }
      }
    } else {
      // Load details of all active rules
      for (RuleDto rule : rules) {
        List<ActiveRule> activeRules = loader.findActiveRulesByRule(rule.getKey());
        List<ActiveRuleDto> activeRuleDtos = dbClient.activeRuleDao().selectByActiveRuleKeys(dbSession, Lists.transform(activeRules, ActiveRuleToKey.INSTANCE));
        Map<Integer, ActiveRuleKey> activeRuleIdsByKey = new HashMap<>();
        for (ActiveRuleDto activeRuleDto : activeRuleDtos) {
          activeRuleIdsByKey.put(activeRuleDto.getId(), activeRuleDto.getKey());
        }

        List<ActiveRuleParamDto> activeRuleParamDtos = dbClient.activeRuleDao().selectParamsByActiveRuleIds(dbSession, Lists.transform(activeRuleDtos, ActiveRuleDtoToId.INSTANCE));
        ListMultimap<ActiveRuleKey, ActiveRuleParamDto> activeRuleParamsByActiveRuleKey = ArrayListMultimap.create(activeRules.size(), 10);
        for (ActiveRuleParamDto activeRuleParamDto : activeRuleParamDtos) {
          ActiveRuleKey activeRuleKey = activeRuleIdsByKey.get(activeRuleParamDto.getId());
          activeRuleParamsByActiveRuleKey.put(activeRuleKey, activeRuleParamDto);
        }

        qProfileKeys = writeActiveRules(rule.getKey(), activeRules, activeRuleParamsByActiveRuleKey, activesBuilder);
      }
    }

    response.setActives(activesBuilder);
    return qProfileKeys;
  }

  void completeShow(Rule rule, ShowResponse.Builder response) {
    for (ActiveRule activeRule : loader.findActiveRulesByRule(rule.key())) {
      response.addActives(buildActiveRuleResponse(activeRule, Collections.<ActiveRuleParamDto>emptyList()));
    }
  }

  private static Collection<String> writeActiveRules(RuleKey ruleKey, Collection<ActiveRule> activeRules,
    ListMultimap<ActiveRuleKey, ActiveRuleParamDto> activeRuleParamsByActiveRuleKey, Rules.Actives.Builder activesBuilder) {
    Collection<String> qProfileKeys = newHashSet();
    Rules.ActiveList.Builder activeRulesListResponse = Rules.ActiveList.newBuilder();
    for (ActiveRule activeRule : activeRules) {
      activeRulesListResponse.addActiveList(buildActiveRuleResponse(activeRule, activeRuleParamsByActiveRuleKey.get(activeRule.key())));
      qProfileKeys.add(activeRule.key().qProfile());
    }
    activesBuilder
      .getMutableActives()
      .put(ruleKey.toString(), activeRulesListResponse.build());
    return qProfileKeys;
  }

  private static Rules.Active buildActiveRuleResponse(ActiveRule activeRule, List<ActiveRuleParamDto> parameters) {
    Rules.Active.Builder activeRuleResponse = Rules.Active.newBuilder();
    activeRuleResponse.setQProfile(activeRule.key().qProfile());
    activeRuleResponse.setInherit(activeRule.inheritance().toString());
    activeRuleResponse.setSeverity(activeRule.severity());
    ActiveRuleKey parentKey = activeRule.parentKey();
    if (parentKey != null) {
      activeRuleResponse.setParent(parentKey.toString());
    }
    Rules.Active.Param.Builder paramBuilder = Rules.Active.Param.newBuilder();
    for (ActiveRuleParamDto parameter : parameters) {
      activeRuleResponse.addParams(paramBuilder.clear()
        .setKey(parameter.getKey())
        .setValue(nullToEmpty(parameter.getValue())));
    }

    return activeRuleResponse.build();
  }

  private Rules.QProfiles.Builder buildQProfiles(Collection<String> harvestedProfileKeys) {
    Map<String, QualityProfileDto> qProfilesByKey = Maps.newHashMap();
    for (String qProfileKey : harvestedProfileKeys) {
      if (!qProfilesByKey.containsKey(qProfileKey)) {
        QualityProfileDto profile = loadProfile(qProfileKey);
        if (profile == null) {
          LOG.warn("Could not find quality profile with key " + qProfileKey);
          continue;
        }
        qProfilesByKey.put(qProfileKey, profile);
        String parentKee = profile.getParentKee();
        if (parentKee != null && !qProfilesByKey.containsKey(parentKee)) {
          qProfilesByKey.put(parentKee, loadProfile(parentKee));
        }
      }
    }

    Rules.QProfiles.Builder qProfilesResponse = Rules.QProfiles.newBuilder();
    Map<String, Rules.QProfile> qProfilesMapResponse = qProfilesResponse.getMutableQProfiles();
    for (QualityProfileDto profile : qProfilesByKey.values()) {
      writeProfile(qProfilesMapResponse, profile);
    }

    return qProfilesResponse;
  }

  @CheckForNull
  QualityProfileDto loadProfile(String qProfileKey) {
    return loader.getByKey(qProfileKey);
  }

  private void writeProfile(Map<String, Rules.QProfile> profilesResponse, QualityProfileDto profile) {
    Rules.QProfile.Builder profileResponse = Rules.QProfile.newBuilder();
    if (profile.getName() != null) {
      profileResponse.setName(profile.getName());
    }
    if (profile.getLanguage() != null) {
      profileResponse.setLang(profile.getLanguage());
      Language language = languages.get(profile.getLanguage());
      String langName = language == null ? profile.getLanguage() : language.getName();
      profileResponse.setLangName(langName);
    }
    if (profile.getParentKee() != null) {
      profileResponse.setParent(profile.getParentKee());
    }

    profilesResponse.put(profile.getKey(), profileResponse.build());
  }

  private enum ActiveRuleToKey implements Function<ActiveRule, ActiveRuleKey> {
    INSTANCE;

    @Override
    public ActiveRuleKey apply(@Nonnull ActiveRule input) {
      return input.key();
    }
  }

  private enum ActiveRuleDtoToId implements Function<ActiveRuleDto, Integer> {
    INSTANCE;

    @Override
    public Integer apply(@Nonnull ActiveRuleDto input) {
      return input.getId();
    }
  }

}
