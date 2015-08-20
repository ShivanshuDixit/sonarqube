/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.computation.formula.coverage;

import org.sonar.server.computation.formula.LeafAggregateContext;

import static org.sonar.server.computation.formula.coverage.CoverageUtils.getLongMeasureValue;

public final class LinesAndConditionsWithUncoveredCounter extends ElementsAndCoveredElementsCounter {
  private final LinesAndConditionsWithUncoveredMetricKeys metricKeys;

  public LinesAndConditionsWithUncoveredCounter(LinesAndConditionsWithUncoveredMetricKeys metricKeys) {
    this.metricKeys = metricKeys;
  }

  @Override
  protected void aggregateForSupportedLeaf(LeafAggregateContext counterContext) {
    this.elements = getLongMeasureValue(counterContext, metricKeys.getLines())
      + getLongMeasureValue(counterContext, metricKeys.getConditions());
    this.coveredElements = this.elements
      - getLongMeasureValue(counterContext, metricKeys.getUncoveredLines())
      - getLongMeasureValue(counterContext, metricKeys.getUncoveredConditions());
  }
}
