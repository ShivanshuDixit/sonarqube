/*
 * SonarQube
 * Copyright (C) 2009-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
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
package org.sonar.server.view.index;

import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.elasticsearch.action.search.SearchResponse;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.sonar.api.config.internal.MapSettings;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.ComponentTesting;
import org.sonar.db.issue.IssueDto;
import org.sonar.db.issue.IssueTesting;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.rule.RuleDto;
import org.sonar.db.rule.RuleTesting;
import org.sonar.server.es.EsTester;
import org.sonar.server.es.RecoveryIndexer;
import org.sonar.server.es.SearchOptions;
import org.sonar.server.issue.IssueQuery;
import org.sonar.server.issue.index.IssueIndex;
import org.sonar.server.issue.index.IssueIndexer;
import org.sonar.server.issue.index.IssueIteratorFactory;
import org.sonar.server.permission.index.AuthorizationTypeSupport;
import org.sonar.server.permission.index.PermissionIndexer;
import org.sonar.server.tester.UserSessionRule;

import static com.google.common.collect.Lists.newArrayList;
import static java.util.Arrays.asList;
import static java.util.Collections.emptySet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.sonar.api.resources.Qualifiers.APP;
import static org.sonar.db.component.ComponentTesting.newProjectCopy;
import static org.sonar.server.view.index.ViewIndexDefinition.INDEX_TYPE_VIEW;

public class ViewIndexerTest {

  private System2 system2 = System2.INSTANCE;

  @Rule
  public TestRule safeguardTimeout = new DisableOnDebug(Timeout.seconds(60));
  @Rule
  public DbTester db = DbTester.create();
  @Rule
  public EsTester es = EsTester.create();
  @Rule
  public UserSessionRule userSessionRule = UserSessionRule.standalone();

  private DbClient dbClient = db.getDbClient();
  private DbSession dbSession = db.getSession();
  private IssueIndexer issueIndexer = new IssueIndexer(es.client(), dbClient, new IssueIteratorFactory(dbClient));
  private PermissionIndexer permissionIndexer = new PermissionIndexer(dbClient, es.client(), issueIndexer);
  private ViewIndexer underTest = new ViewIndexer(dbClient, es.client());

  @Test
  public void index_nothing() {
    underTest.indexOnStartup(emptySet());
    assertThat(es.countDocuments(ViewIndexDefinition.INDEX_TYPE_VIEW)).isEqualTo(0L);
  }

  @Test
  public void index_on_startup() {
    db.prepareDbUnit(getClass(), "index.xml");

    underTest.indexOnStartup(emptySet());

    List<ViewDoc> docs = es.getDocuments(ViewIndexDefinition.INDEX_TYPE_VIEW, ViewDoc.class);
    assertThat(docs).hasSize(4);

    Map<String, ViewDoc> viewsByUuid = Maps.uniqueIndex(docs, ViewDoc::uuid);

    assertThat(viewsByUuid.get("ABCD").projects()).containsOnly("JKLM");
    assertThat(viewsByUuid.get("EFGH").projects()).containsOnly("KLMN", "JKLM");
    assertThat(viewsByUuid.get("FGHI").projects()).containsOnly("JKLM");
    assertThat(viewsByUuid.get("IJKL").projects()).isEmpty();
  }

  @Test
  public void index_root_view() {
    db.prepareDbUnit(getClass(), "index.xml");

    underTest.index("EFGH");

    List<ViewDoc> docs = es.getDocuments(ViewIndexDefinition.INDEX_TYPE_VIEW, ViewDoc.class);
    assertThat(docs).hasSize(2);

    Map<String, ViewDoc> viewsByUuid = Maps.uniqueIndex(docs, ViewDoc::uuid);

    assertThat(viewsByUuid.get("EFGH").projects()).containsOnly("KLMN", "JKLM");
    assertThat(viewsByUuid.get("FGHI").projects()).containsOnly("JKLM");
  }

  @Test
  public void index_view_doc() {
    underTest.index(new ViewDoc().setUuid("EFGH").setProjects(newArrayList("KLMN", "JKLM")));

    List<ViewDoc> result = es.getDocuments(ViewIndexDefinition.INDEX_TYPE_VIEW, ViewDoc.class);

    assertThat(result).hasSize(1);
    ViewDoc view = result.get(0);
    assertThat(view.uuid()).isEqualTo("EFGH");
    assertThat(view.projects()).containsOnly("KLMN", "JKLM");
  }

  @Test
  public void index_application() {
    ComponentDto application = db.components().insertApplication(db.getDefaultOrganization());
    ComponentDto project = db.components().insertPrivateProject();
    db.components().insertComponent(newProjectCopy("PC1", project, application));

    underTest.index(application.uuid());
    List<ViewDoc> result = es.getDocuments(ViewIndexDefinition.INDEX_TYPE_VIEW, ViewDoc.class);

    assertThat(result).hasSize(1);
    ViewDoc resultApp = result.get(0);
    assertThat(resultApp.uuid()).isEqualTo(application.uuid());
    assertThat(resultApp.projects()).containsExactlyInAnyOrder(project.uuid());
  }

  @Test
  public void index_application_on_startup() {
    ComponentDto application = db.components().insertApplication(db.getDefaultOrganization());
    ComponentDto project = db.components().insertPrivateProject();
    db.components().insertComponent(newProjectCopy("PC1", project, application));

    underTest.indexOnStartup(emptySet());
    List<ViewDoc> result = es.getDocuments(ViewIndexDefinition.INDEX_TYPE_VIEW, ViewDoc.class);

    assertThat(result).hasSize(1);
    ViewDoc resultApp = result.get(0);
    assertThat(resultApp.uuid()).isEqualTo(application.uuid());
    assertThat(resultApp.projects()).containsExactlyInAnyOrder(project.uuid());
  }

  @Test
  public void index_application_branch() {
    ComponentDto application = db.components().insertMainBranch(c -> c.setQualifier(APP).setDbKey("app"));
    ComponentDto applicationBranch1 = db.components().insertProjectBranch(application, a -> a.setKey("app-branch1"));
    ComponentDto applicationBranch2 = db.components().insertProjectBranch(application, a -> a.setKey("app-branch2"));
    ComponentDto project1 = db.components().insertPrivateProject(p -> p.setDbKey("prj1"));
    ComponentDto project1Branch = db.components().insertProjectBranch(project1);
    ComponentDto project2 = db.components().insertPrivateProject(p -> p.setDbKey("prj2"));
    ComponentDto project2Branch = db.components().insertProjectBranch(project2);
    ComponentDto project3 = db.components().insertPrivateProject(p -> p.setDbKey("prj3"));
    ComponentDto project3Branch = db.components().insertProjectBranch(project3);
    db.components().insertComponent(newProjectCopy(project1Branch, applicationBranch1));
    db.components().insertComponent(newProjectCopy(project2Branch, applicationBranch1));
    // Technical project of applicationBranch2 should be ignored
    db.components().insertComponent(newProjectCopy(project3Branch, applicationBranch2));

    underTest.index(applicationBranch1.uuid());

    List<ViewDoc> result = es.getDocuments(ViewIndexDefinition.INDEX_TYPE_VIEW, ViewDoc.class);
    assertThat(result)
      .extracting(ViewDoc::uuid, ViewDoc::projects)
      .containsExactlyInAnyOrder(
        tuple(applicationBranch1.uuid(), asList(project1Branch.uuid(), project2Branch.uuid())));
  }

  @Test
  public void clear_views_lookup_cache_on_index_view_uuid() {
    IssueIndex issueIndex = new IssueIndex(es.client(), System2.INSTANCE, userSessionRule, new AuthorizationTypeSupport(userSessionRule));
    IssueIndexer issueIndexer = new IssueIndexer(es.client(), dbClient, new IssueIteratorFactory(dbClient));

    String viewUuid = "ABCD";

    RuleDto rule = RuleTesting.newXooX1();
    dbClient.ruleDao().insert(dbSession, rule.getDefinition());
    ComponentDto project1 = addProjectWithIssue(rule, db.organizations().insert());
    issueIndexer.indexOnStartup(issueIndexer.getIndexTypes());
    permissionIndexer.indexOnStartup(permissionIndexer.getIndexTypes());

    OrganizationDto organizationDto = db.organizations().insert();
    ComponentDto view = ComponentTesting.newView(organizationDto, "ABCD");
    ComponentDto techProject1 = newProjectCopy("CDEF", project1, view);
    dbClient.componentDao().insert(dbSession, view, techProject1);
    dbSession.commit();

    // First view indexation
    underTest.index(viewUuid);

    // Execute issue query on view -> 1 issue on view
    SearchResponse issueResponse = issueIndex.search(IssueQuery.builder().viewUuids(newArrayList(viewUuid)).build(), new SearchOptions());
    assertThat(issueResponse.getHits().getHits()).hasSize(1);

    // Add a project to the view and index it again
    ComponentDto project2 = addProjectWithIssue(rule, organizationDto);
    issueIndexer.indexOnStartup(issueIndexer.getIndexTypes());
    permissionIndexer.indexOnStartup(permissionIndexer.getIndexTypes());

    ComponentDto techProject2 = newProjectCopy("EFGH", project2, view);
    dbClient.componentDao().insert(dbSession, techProject2);
    dbSession.commit();
    underTest.index(viewUuid);

    // Execute issue query on view -> issue of project2 are well taken into account : the cache has been cleared
    assertThat(issueIndex.search(IssueQuery.builder().viewUuids(newArrayList(viewUuid)).build(), new SearchOptions()).getHits()).hasSize(2);
  }

  @Test
  public void delete_should_delete_the_view() {
    ViewDoc view1 = new ViewDoc().setUuid("UUID1").setProjects(asList("P1"));
    ViewDoc view2 = new ViewDoc().setUuid("UUID2").setProjects(asList("P2", "P3", "P4"));
    ViewDoc view3 = new ViewDoc().setUuid("UUID3").setProjects(asList("P2", "P3", "P4"));
    es.putDocuments(INDEX_TYPE_VIEW, view1);
    es.putDocuments(INDEX_TYPE_VIEW, view2);
    es.putDocuments(INDEX_TYPE_VIEW, view3);

    assertThat(es.getDocumentFieldValues(INDEX_TYPE_VIEW, ViewIndexDefinition.FIELD_UUID))
      .containsOnly(view1.uuid(), view2.uuid(), view3.uuid());

    underTest.delete(dbSession, asList(view1.uuid(), view2.uuid()));

    assertThat(es.getDocumentFieldValues(INDEX_TYPE_VIEW, ViewIndexDefinition.FIELD_UUID))
      .containsOnly(view3.uuid());
  }

  @Test
  public void delete_should_be_resilient() throws InterruptedException {
    ViewDoc view1 = new ViewDoc().setUuid("UUID1").setProjects(asList("P1"));
    ViewDoc view2 = new ViewDoc().setUuid("UUID2").setProjects(asList("P2", "P3", "P4"));
    ViewDoc view3 = new ViewDoc().setUuid("UUID3").setProjects(asList("P2", "P3", "P4"));
    es.putDocuments(INDEX_TYPE_VIEW, view1);
    es.putDocuments(INDEX_TYPE_VIEW, view2);
    es.putDocuments(INDEX_TYPE_VIEW, view3);

    assertThat(es.getDocumentFieldValues(INDEX_TYPE_VIEW, ViewIndexDefinition.FIELD_UUID))
      .containsOnly(view1.uuid(), view2.uuid(), view3.uuid());

    // Lock writes
    es.lockWrites(INDEX_TYPE_VIEW);
    underTest.delete(dbSession, asList(view1.uuid(), view2.uuid()));

    assertThat(es.getDocumentFieldValues(INDEX_TYPE_VIEW, ViewIndexDefinition.FIELD_UUID))
      .containsOnly(view1.uuid(), view2.uuid(), view3.uuid());

    // Unlock writes
    es.unlockWrites(INDEX_TYPE_VIEW);

    doRecover(() -> es.getDocumentFieldValues(INDEX_TYPE_VIEW, ViewIndexDefinition.FIELD_UUID).size() == 3);

    assertThat(es.getDocumentFieldValues(INDEX_TYPE_VIEW, ViewIndexDefinition.FIELD_UUID))
      .containsOnly(view3.uuid());
  }

  private ComponentDto addProjectWithIssue(RuleDto rule, OrganizationDto org) {
    ComponentDto project = ComponentTesting.newPublicProjectDto(org);
    ComponentDto file = ComponentTesting.newFileDto(project, null);
    db.components().insertComponents(project, file);

    IssueDto issue = IssueTesting.newDto(rule, file, project);
    dbClient.issueDao().insert(dbSession, issue);
    dbSession.commit();

    return project;
  }

  private void doRecover(BooleanSupplier condition) throws InterruptedException {
    MapSettings settings = new MapSettings()
      .setProperty("sonar.search.recovery.initialDelayInMs", "0")
      .setProperty("sonar.search.recovery.minAgeInMs", "1")
      .setProperty("sonar.search.recovery.delayInMs", "1");

    RecoveryIndexer recoveryIndexer = new RecoveryIndexer(System2.INSTANCE, settings.asConfig(), dbClient, underTest);
    recoveryIndexer.start();

    // Wait for recovery
    while (condition.getAsBoolean()) {
      Thread.sleep(1_000);
    }

    recoveryIndexer.stop();
  }
}
