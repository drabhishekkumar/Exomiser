/*
 * The Exomiser - A tool to annotate and prioritize variants
 *
 * Copyright (C) 2012 - 2016  Charite Universitätsmedizin Berlin and Genome Research Ltd.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.monarchinitiative.exomiser.core;

import de.charite.compbio.jannovar.data.JannovarData;
import org.junit.Before;
import org.junit.Test;
import org.monarchinitiative.exomiser.core.analysis.Analysis;
import org.monarchinitiative.exomiser.core.analysis.AnalysisFactory;
import org.monarchinitiative.exomiser.core.analysis.AnalysisMode;
import org.monarchinitiative.exomiser.core.analysis.AnalysisResults;
import org.monarchinitiative.exomiser.core.factories.TestFactory;
import org.monarchinitiative.exomiser.core.factories.VariantDataService;
import org.monarchinitiative.exomiser.core.factories.VariantDataServiceStub;
import org.monarchinitiative.exomiser.core.prioritisers.PriorityFactoryImpl;

import java.nio.file.Paths;

/**
 *
 * @author Jules Jacobsen <jules.jacobsen@sanger.ac.uk>
 */
public class ExomiserTest {
 
    private Exomiser instance;
        
    private final JannovarData jannovarData = TestFactory.buildDefaultJannovarData();
    private final VariantDataService stubDataService = new VariantDataServiceStub();
    
    private final AnalysisFactory analysisFactory = new AnalysisFactory(jannovarData, new PriorityFactoryImpl(), stubDataService);
    
    @Before
    public void setUp() {
        instance = new Exomiser(analysisFactory);
    }
    
    private Analysis makeAnalysisWithMode(AnalysisMode analysisMode) {
        return Analysis.builder()
                .vcfPath(Paths.get("src/test/resources/smallTest.vcf"))
                .analysisMode(analysisMode)
                .build();
    }
    
    @Test
    public void canRunAnalysis_Full() {
        Analysis analysis = makeAnalysisWithMode(AnalysisMode.FULL);
        AnalysisResults analysisResults = instance.run(analysis);
    }
    
    @Test
    public void canRunAnalysis_Sparse() {
        Analysis analysis = makeAnalysisWithMode(AnalysisMode.SPARSE);
        AnalysisResults analysisResults = instance.run(analysis);
    }
    
    @Test
    public void canRunAnalysis_PassOnly() {
        Analysis analysis = makeAnalysisWithMode(AnalysisMode.PASS_ONLY);
        AnalysisResults analysisResults = instance.run(analysis);
    }
    
 }
