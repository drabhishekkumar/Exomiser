/*
 * The Exomiser - A tool to annotate and prioritize genomic variants
 *
 * Copyright (c) 2016-2020 Queen Mary University of London.
 * Copyright (c) 2012-2016 Charité Universitätsmedizin Berlin and Genome Research Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.monarchinitiative.exomiser.core.analysis;

import com.google.common.collect.ImmutableList;
import de.charite.compbio.jannovar.annotation.VariantEffect;
import de.charite.compbio.jannovar.mendel.SubModeOfInheritance;
import org.monarchinitiative.exomiser.api.v1.*;
import org.monarchinitiative.exomiser.core.analysis.sample.Sample;
import org.monarchinitiative.exomiser.core.analysis.util.InheritanceModeOptions;
import org.monarchinitiative.exomiser.core.genome.BedFiles;
import org.monarchinitiative.exomiser.core.genome.GenomeAnalysisServiceProvider;
import org.monarchinitiative.exomiser.core.genome.GenomeAssembly;
import org.monarchinitiative.exomiser.core.genome.UnsupportedGenomeAssemblyException;
import org.monarchinitiative.exomiser.core.model.ChromosomalRegion;
import org.monarchinitiative.exomiser.core.model.GeneticInterval;
import org.monarchinitiative.exomiser.core.model.frequency.FrequencySource;
import org.monarchinitiative.exomiser.core.model.pathogenicity.PathogenicitySource;
import org.monarchinitiative.exomiser.core.phenotype.service.OntologyService;
import org.monarchinitiative.exomiser.core.prioritisers.HiPhiveOptions;
import org.monarchinitiative.exomiser.core.prioritisers.PriorityFactory;
import org.monarchinitiative.exomiser.core.prioritisers.PriorityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Paths;
import java.util.*;
import java.util.Map.Entry;

import static java.util.stream.Collectors.toList;
import static org.monarchinitiative.exomiser.core.model.pathogenicity.PathogenicitySource.*;

/**
 * Class for parsing {@link Sample} and {@link Analysis} domain objects out of the protobuf {@link JobProto} objects.
 * The {@link JobProto} classes are autogenerated from a protobuf schema representing the public classes used in an
 * Exomiser analysis which can be specified to the system by the user via the fluent API exposed by the
 * {@link AnalysisProtoBuilder} (for protobuf objects) or the {@link AnalysisBuilder} (for domain objects) or in yaml or
 * json files which can be read using the {@link JobReader} and then converted using this class into domain objects.
 * <p>
 * The protobuf classes can be though of as direct object-representations of the yaml/json but they lack the necessary
 * apparatus to actually run which is provided by this {@link JobParser} and ultimately the {@link AnalysisRunner}.
 *
 * @author Jules Jacobsen <j.jacobsen@qmul.ac.uk>
 * @since 13.0.0
 */
@Component
public class JobParser {

    private static final InheritanceModeOptions DEFAULT_INHERITANCE_MODE_OPTIONS;

    static {
        Map<SubModeOfInheritance, Float> inheritanceModeFrequencyCutoffs = new EnumMap<>(SubModeOfInheritance.class);
        // all frequencies are in percentage values
        inheritanceModeFrequencyCutoffs.put(SubModeOfInheritance.AUTOSOMAL_DOMINANT, 0.1f);
        inheritanceModeFrequencyCutoffs.put(SubModeOfInheritance.AUTOSOMAL_RECESSIVE_COMP_HET, 2.0f);
        inheritanceModeFrequencyCutoffs.put(SubModeOfInheritance.AUTOSOMAL_RECESSIVE_HOM_ALT, 0.1f);
        inheritanceModeFrequencyCutoffs.put(SubModeOfInheritance.X_DOMINANT, 0.1f);
        inheritanceModeFrequencyCutoffs.put(SubModeOfInheritance.X_RECESSIVE_COMP_HET, 2.0f);
        inheritanceModeFrequencyCutoffs.put(SubModeOfInheritance.X_RECESSIVE_HOM_ALT, 0.1f);
        inheritanceModeFrequencyCutoffs.put(SubModeOfInheritance.MITOCHONDRIAL, 0.2f);
        DEFAULT_INHERITANCE_MODE_OPTIONS = InheritanceModeOptions.of(inheritanceModeFrequencyCutoffs);
    }

    private static final EnumSet<FrequencySource> DEFAULT_FREQUENCY_SOURCES = EnumSet.of(
            FrequencySource.ESP_AFRICAN_AMERICAN, FrequencySource.ESP_ALL, FrequencySource.ESP_EUROPEAN_AMERICAN,
            FrequencySource.THOUSAND_GENOMES,
            FrequencySource.EXAC_AFRICAN_INC_AFRICAN_AMERICAN, FrequencySource.EXAC_AMERICAN,
            FrequencySource.EXAC_EAST_ASIAN, FrequencySource.EXAC_FINNISH,
            FrequencySource.EXAC_NON_FINNISH_EUROPEAN, FrequencySource.EXAC_SOUTH_ASIAN, FrequencySource.EXAC_OTHER,
            FrequencySource.UK10K, FrequencySource.TOPMED,
            FrequencySource.GNOMAD_E_AFR, FrequencySource.GNOMAD_E_AMR,
            //FrequencySource.GNOMAD_E_ASJ,
            FrequencySource.GNOMAD_E_EAS, FrequencySource.GNOMAD_E_FIN,
            FrequencySource.GNOMAD_E_NFE, FrequencySource.GNOMAD_E_OTH, FrequencySource.GNOMAD_E_SAS,
            FrequencySource.GNOMAD_G_AFR, FrequencySource.GNOMAD_G_AMR,
            //FrequencySource.GNOMAD_G_ASJ,
            FrequencySource.GNOMAD_G_EAS, FrequencySource.GNOMAD_G_FIN,
            FrequencySource.GNOMAD_G_NFE, FrequencySource.GNOMAD_G_OTH, FrequencySource.GNOMAD_G_SAS
    );

    private static final HiPhiveOptions HI_PHIVE_OPTIONS = HiPhiveOptions.builder()
            .runParams("human,mouse,fish,ppi")
            .build();

    private static final Logger logger = LoggerFactory.getLogger(JobParser.class);

    private final GenomeAnalysisServiceProvider genomeAnalysisServiceProvider;
    private final PriorityFactory prioritiserFactory;
    private final OntologyService ontologyService;

    @Autowired
    public JobParser(GenomeAnalysisServiceProvider genomeAnalysisServiceProvider, PriorityFactory prioritiserFactory, OntologyService ontologyService) {
        this.genomeAnalysisServiceProvider = genomeAnalysisServiceProvider;
        this.prioritiserFactory = prioritiserFactory;
        this.ontologyService = ontologyService;
    }

    public Sample parseSample(JobProto.Job protoJob) {
        Objects.requireNonNull(protoJob);
        Sample sample = getSample(protoJob);
        checkAssemblySupportedOrThrowException(sample.getGenomeAssembly());
        return sampleWithUpdatedHpoIds(sample);
    }

    public Analysis parseAnalysis(JobProto.Job protoJob) {
        Objects.requireNonNull(protoJob);
        // CAUTION! analysis / preset are part of a oneof in the protobuf schema. Therefore,
        // ensure that hasAnalysis is checked first as the Preset will always be set to EXOMISER
        if (protoJob.hasAnalysis()) {
            return parseProtoAnalysis(protoJob.getAnalysis());
        }
        return parsePreset(protoJob);
    }

    public Analysis exomePreset() {
        return buildExomePreset();
    }

    public Analysis genomePreset() {
        return buildGenomePreset();
    }

    private Sample getSample(JobProto.Job protoJob) {
        if (protoJob.hasSample()) {
            return Sample.from(protoJob.getSample());
        }
        if (protoJob.hasPhenopacket()) {
            return Sample.from(protoJob.getPhenopacket());
        }
        if (protoJob.hasFamily()) {
            return Sample.from(protoJob.getFamily());
        }
        if (onlyHasAnalysis(protoJob)) {
            SampleProto.Sample protoSample = sampleFromProtoAnalysis(protoJob.getAnalysis());
            return Sample.from(protoSample);
        }
        throw new IllegalStateException("Unsupported Sample type - only Sample, Family or Phenopacket currently supported.");
    }

    private boolean onlyHasAnalysis(JobProto.Job protoJob) {
        return protoJob.hasAnalysis() && !protoJob.hasSample() && !protoJob.hasPhenopacket() && !protoJob.hasFamily();
    }

    private void checkAssemblySupportedOrThrowException(GenomeAssembly genomeAssembly) {
        if (!genomeAnalysisServiceProvider.hasServiceFor(genomeAssembly)) {
            throw new UnsupportedGenomeAssemblyException(String.format("Assembly %s not supported in this instance. Supported assemblies are: %s", genomeAssembly, genomeAnalysisServiceProvider
                    .getProvidedAssemblies()));
        }
    }

    private Sample sampleWithUpdatedHpoIds(Sample sample) {
        List<String> originalHpoIds = sample.getHpoIds();
        List<String> currentHpoIds = ontologyService.getCurrentHpoIds(sample.getHpoIds());
        if (originalHpoIds.equals(currentHpoIds)) {
            return sample;
        }
        return Sample.builder()
                .from(sample)
                .hpoIds(currentHpoIds)
                .build();
    }

    private SampleProto.Sample sampleFromProtoAnalysis(AnalysisProto.Analysis protoAnalysis) {
        return SampleProto.Sample.newBuilder()
                .setVcf(protoAnalysis.getVcf())
                .setGenomeAssembly(protoAnalysis.getGenomeAssembly())
                .setPed(protoAnalysis.getPed())
                .setProband(protoAnalysis.getProband())
                .addAllHpoIds(protoAnalysis.getHpoIdsList())
                .build();
    }

    private Analysis parsePreset(JobProto.Job protoJob) {
        switch (protoJob.getPreset()) {
            case GENOME:
                logger.info("Running GENOME preset");
                return buildGenomePreset();
            case EXOME:
            default:
                logger.info("Running EXOME preset");
                return buildExomePreset();
        }
    }

    private Analysis buildGenomePreset() {
        return new AnalysisBuilder(genomeAnalysisServiceProvider, prioritiserFactory, ontologyService)
                .analysisMode(AnalysisMode.PASS_ONLY)
                .inheritanceModes(DEFAULT_INHERITANCE_MODE_OPTIONS)
                .frequencySources(DEFAULT_FREQUENCY_SOURCES)
                .pathogenicitySources(EnumSet.of(REVEL, MVP, REMM))
                .addHiPhivePrioritiser(HI_PHIVE_OPTIONS)
                .addPriorityScoreFilter(PriorityType.HIPHIVE_PRIORITY, 0.5f)// will remove a lot of the weak PPI hits
                .addFailedVariantFilter()
                .addRegulatoryFeatureFilter()
                .addFrequencyFilter()
                .addPathogenicityFilter(true)
                .addInheritanceFilter()
                .addOmimPrioritiser()
                .build();
    }

    private Analysis buildExomePreset() {
        return new AnalysisBuilder(genomeAnalysisServiceProvider, prioritiserFactory, ontologyService)
                .analysisMode(AnalysisMode.PASS_ONLY)
                .inheritanceModes(DEFAULT_INHERITANCE_MODE_OPTIONS)
                .frequencySources(DEFAULT_FREQUENCY_SOURCES)
                .pathogenicitySources(EnumSet.of(REVEL, MVP))
                .addVariantEffectFilter(EnumSet.of(
                        VariantEffect.FIVE_PRIME_UTR_EXON_VARIANT,
                        VariantEffect.FIVE_PRIME_UTR_INTRON_VARIANT,
                        VariantEffect.THREE_PRIME_UTR_EXON_VARIANT,
                        VariantEffect.THREE_PRIME_UTR_INTRON_VARIANT,
                        VariantEffect.NON_CODING_TRANSCRIPT_EXON_VARIANT,
                        VariantEffect.NON_CODING_TRANSCRIPT_INTRON_VARIANT,
                        VariantEffect.CODING_TRANSCRIPT_INTRON_VARIANT,
                        VariantEffect.UPSTREAM_GENE_VARIANT,
                        VariantEffect.DOWNSTREAM_GENE_VARIANT,
                        VariantEffect.INTERGENIC_VARIANT,
                        VariantEffect.REGULATORY_REGION_VARIANT
                ))
                .addFailedVariantFilter()
                .addFrequencyFilter()
                .addPathogenicityFilter(true)
                .addInheritanceFilter()
                .addOmimPrioritiser()
                .addHiPhivePrioritiser(HI_PHIVE_OPTIONS)
                .build();
    }

    private Analysis parseProtoAnalysis(AnalysisProto.Analysis protoAnalysis) {
        InheritanceModeOptions inheritanceModeOptions = inheritanceModeOptions(protoAnalysis.getInheritanceModesMap());
        Set<FrequencySource> frequencySources = parseFrequencySources(protoAnalysis.getFrequencySourcesList());
        Set<PathogenicitySource> pathogenicitySources = parsePathogenicitySources(protoAnalysis.getPathogenicitySourcesList());
        // TODO: Should this be an AnalysisProtoConverter class exposed via AnalysisBuilder.from(AnalysisProto.Analysis protoAnalysis)
        //  so that the external API remains consistent?
        AnalysisBuilder analysisBuilder = new AnalysisBuilder(genomeAnalysisServiceProvider, prioritiserFactory, ontologyService)
                .inheritanceModes(inheritanceModeOptions)
                .analysisMode(parseAnalysisMode(protoAnalysis.getAnalysisMode()))
                .frequencySources(frequencySources)
                .pathogenicitySources(pathogenicitySources);

        for (AnalysisProto.AnalysisStep analysisStep : protoAnalysis.getStepsList()) {
            addAnalysisStep(analysisBuilder, inheritanceModeOptions, frequencySources, pathogenicitySources, analysisStep);
        }

        return analysisBuilder.build();
    }

    private InheritanceModeOptions inheritanceModeOptions(Map<String, Float> inheritanceModesMap) {
        Map<SubModeOfInheritance, Float> inheritanceModeCutOffs = new EnumMap<>(SubModeOfInheritance.class);

        for (Entry<String, Float> entry : inheritanceModesMap.entrySet()) {
            inheritanceModeCutOffs.put(SubModeOfInheritance.valueOf(entry.getKey()), entry.getValue());
        }

        return inheritanceModeCutOffs.isEmpty() ? InheritanceModeOptions.empty() : InheritanceModeOptions.of(inheritanceModeCutOffs);
    }

    private AnalysisMode parseAnalysisMode(AnalysisProto.AnalysisMode analysisMode) {
        return analysisMode == AnalysisProto.AnalysisMode.FULL ? AnalysisMode.FULL : AnalysisMode.PASS_ONLY;
    }

    private Set<FrequencySource> parseFrequencySources(Iterable<String> frequencySourcesList) {
        // This will affect the Frequency and KnownVariantFilter if empty, but they are checked by the AnalysisBuilder
        // so no need to do so here.
        Set<FrequencySource> frequencySources = EnumSet.noneOf(FrequencySource.class);
        for (String source : frequencySourcesList) {
            frequencySources.add(FrequencySource.valueOf(source));
        }
        return frequencySources;
    }

    private Set<PathogenicitySource> parsePathogenicitySources(Iterable<String> pathogenicitySourcesList) {
        // This will affect the PathogenicityFilter if empty, but they are checked by the AnalysisBuilder
        // so no need to do so here.
        Set<PathogenicitySource> pathogenicitySources = EnumSet.noneOf(PathogenicitySource.class);
        for (String source : pathogenicitySourcesList) {
            pathogenicitySources.add(valueOf(source));
        }
        return pathogenicitySources;
    }

    private void addAnalysisStep(AnalysisBuilder analysisBuilder, InheritanceModeOptions inheritanceModeOptions, Set<FrequencySource> frequencySources, Set<PathogenicitySource> pathogenicitySources, AnalysisProto.AnalysisStep protoAnalysisStep) {
        if (protoAnalysisStep.hasFailedVariantFilter()) {
            analysisBuilder.addFailedVariantFilter();
        } else if (protoAnalysisStep.hasIntervalFilter()) {
            List<ChromosomalRegion> chromosomalRegions = parseIntervalFilterOptions(protoAnalysisStep.getIntervalFilter());
            analysisBuilder.addIntervalFilter(chromosomalRegions);
        } else if (protoAnalysisStep.hasGenePanelFilter()) {
            Set<String> genesToKeep = parseGeneSymbolFilterOptions(protoAnalysisStep.getGenePanelFilter());
            analysisBuilder.addGeneIdFilter(genesToKeep);
        } else if (protoAnalysisStep.hasVariantEffectFilter()) {
            Set<VariantEffect> variantEffects = parseVariantEffectFilterOptions(protoAnalysisStep.getVariantEffectFilter());
            analysisBuilder.addVariantEffectFilter(variantEffects);
        } else if (protoAnalysisStep.hasQualityFilter()) {
            double quality = parseQualityFilterOptions(protoAnalysisStep.getQualityFilter());
            analysisBuilder.addQualityFilter(quality);
        } else if (protoAnalysisStep.hasKnownVariantFilter()) {
            if (frequencySources.isEmpty()) {
                throw new IllegalStateException("Known variant filter requires a list of frequency sources for the analysis e.g. frequencySources: [THOUSAND_GENOMES, ESP_ALL]");
            }
            analysisBuilder.addKnownVariantFilter();
        } else if (protoAnalysisStep.hasFrequencyFilter()) {
            if (frequencySources.isEmpty()) {
                throw new IllegalStateException("Frequency filter requires a list of frequency sources for the analysis e.g. frequencySources: [THOUSAND_GENOMES, ESP_ALL]");
            }
            float maxFreq = getMaxFreq(inheritanceModeOptions, protoAnalysisStep.getFrequencyFilter());
            analysisBuilder.addFrequencyFilter(maxFreq);
        } else if (protoAnalysisStep.hasPathogenicityFilter()) {
            if (pathogenicitySources.isEmpty()) {
                throw new IllegalStateException("Pathogenicity filter requires a list of pathogenicity sources for the analysis e.g. {pathogenicitySources: [SIFT, POLYPHEN, MUTATION_TASTER]}");
            }
            boolean keepNonPathogenic = getKeepNonPathogenic(protoAnalysisStep.getPathogenicityFilter());
            analysisBuilder.addPathogenicityFilter(keepNonPathogenic);
        } else if (protoAnalysisStep.hasInheritanceFilter()) {
            analysisBuilder.addInheritanceFilter();
        } else if (protoAnalysisStep.hasPriorityScoreFilter()) {
            PriorityType priorityType = parsePriorityType(protoAnalysisStep.getPriorityScoreFilter());
            float minPriorityScore = parseMinPriorityScore(protoAnalysisStep.getPriorityScoreFilter());
            analysisBuilder.addPriorityScoreFilter(priorityType, minPriorityScore);
        } else if (protoAnalysisStep.hasRegulatoryFeatureFilter()) {
            analysisBuilder.addRegulatoryFeatureFilter();
        } else if (protoAnalysisStep.hasOmimPrioritiser()) {
            analysisBuilder.addOmimPrioritiser();
        } else if (protoAnalysisStep.hasHiPhivePrioritiser()) {
            HiPhiveOptions hiPhiveOptions = makeHiPhiveOptions(protoAnalysisStep.getHiPhivePrioritiser());
            analysisBuilder.addHiPhivePrioritiser(hiPhiveOptions);
        } else if (protoAnalysisStep.hasPhivePrioritiser()) {
            analysisBuilder.addPhivePrioritiser();
        } else if (protoAnalysisStep.hasPhenixPrioritiser()) {
//            throw new IllegalArgumentException("phenixPrioritiser is not supported in this release. Please use hiPhivePrioritiser instead.");
            analysisBuilder.addPhenixPrioritiser();
        }
//        case "exomeWalkerPrioritiser":
//        return makeWalkerPrioritiser(analysisStepOptions, analysisBuilder);
    }

    private List<ChromosomalRegion> parseIntervalFilterOptions(FiltersProto.IntervalFilter intervalFilter) {
        if (!intervalFilter.getInterval().isEmpty()) {
            String interval = intervalFilter.getInterval();
            return ImmutableList.of(GeneticInterval.parseString(interval));
        }
        if (!intervalFilter.getIntervalsList().isEmpty()) {
            List<String> intervalStrings = intervalFilter.getIntervalsList();
            List<ChromosomalRegion> intervals = new ArrayList<>();
            intervalStrings.forEach(string -> intervals.add(GeneticInterval.parseString(string)));
            return intervals;
        }
        if (!intervalFilter.getBed().isEmpty()) {
            String bedPath = intervalFilter.getBed();
            return BedFiles.readChromosomalRegions(Paths.get(bedPath)).collect(toList());
        }
        throw new IllegalArgumentException("Interval filter requires a valid genetic interval e.g. {interval: 'chr10:122892600-122892700'} or bed file path {bed: /data/intervals.bed}");
    }

    private Set<String> parseGeneSymbolFilterOptions(FiltersProto.GenePanelFilter genePanelFilter) {
        List<String> geneSymbols = genePanelFilter.getGeneSymbolsList();
        if (geneSymbols == null || geneSymbols.isEmpty()) {
            throw new IllegalArgumentException("Gene panel filter requires a list of HGNC gene symbols e.g. {geneSymbols: [FGFR1, FGFR2]}");
        }
        return new LinkedHashSet<>(geneSymbols);
    }

    private Set<VariantEffect> parseVariantEffectFilterOptions(FiltersProto.VariantEffectFilter variantEffectFilter) {
        List<String> effectsToRemove = variantEffectFilter.getRemoveList();
        if (effectsToRemove == null || effectsToRemove.isEmpty()) {
            throw new IllegalStateException("VariantEffect filter requires a list of VariantEffects to be removed e.g. {remove: [UPSTREAM_GENE_VARIANT, INTERGENIC_VARIANT, SYNONYMOUS_VARIANT]}");
        }
        List<VariantEffect> variantEffects = new ArrayList<>();
        for (String effect : effectsToRemove) {
            try {
                VariantEffect variantEffect = VariantEffect.valueOf(effect);
                variantEffects.add(variantEffect);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(String.format("Illegal VariantEffect: '%s'.%nPermitted effects are any of: %s.", effect, EnumSet
                        .allOf(VariantEffect.class)));
            }
        }
        return EnumSet.copyOf(variantEffects);
    }

    private double parseQualityFilterOptions(FiltersProto.QualityFilter qualityFilter) {
        float quality = qualityFilter.getMinQuality();
        if (quality == 0f) {
            throw new IllegalArgumentException("Quality filter requires a floating point value for the minimum PHRED score e.g. {minQuality: 50.0}");
        }
        return quality;
    }

    private float getMaxFreq(InheritanceModeOptions inheritanceModeOptions, FiltersProto.FrequencyFilter frequencyFilter) {
        float maxFreq = frequencyFilter.getMaxFrequency();
        if (maxFreq == 0 && inheritanceModeOptions.isEmpty()) {
            throw new IllegalStateException("Frequency filter requires a floating point value for the maximum frequency e.g. {maxFrequency: 2.0} if inheritanceModes have not been defined.");
        }
        if (maxFreq == 0 && !inheritanceModeOptions.isEmpty()) {
            logger.debug("maxFrequency not defined - using inheritanceModeOptions max frequency.");
            return inheritanceModeOptions.getMaxFreq();
        }
        return maxFreq;
    }

    private boolean getKeepNonPathogenic(FiltersProto.PathogenicityFilter pathogenicityFilter) {
        // n.b. defaults to false if not set.
        return pathogenicityFilter.getKeepNonPathogenic();
    }

    private PriorityType parsePriorityType(FiltersProto.PriorityScoreFilter priorityScoreFilter) {
        String priorityTypeString = priorityScoreFilter.getPriorityType();
        if (priorityTypeString.isEmpty()) {
            throw new IllegalArgumentException("Priority score filter requires a string value for the prioritiser type e.g. {priorityType: HIPHIVE_PRIORITY}");
        }
        return PriorityType.valueOf(priorityTypeString);
    }

    private float parseMinPriorityScore(FiltersProto.PriorityScoreFilter priorityScoreFilter) {
        float minPriorityScore = priorityScoreFilter.getMinPriorityScore();
        if (minPriorityScore == 0) {
            throw new IllegalArgumentException("Priority score filter requires a floating point value for the minimum prioritiser score e.g. {minPriorityScore: 0.65}");
        }
        return minPriorityScore;
    }

    private HiPhiveOptions makeHiPhiveOptions(PrioritisersProto.HiPhivePrioritiser hiPhivePrioritiser) {
        String diseaseId = hiPhivePrioritiser.getDiseaseId();
        String candidateGeneSymbol = hiPhivePrioritiser.getCandidateGeneSymbol();
        String runParams = hiPhivePrioritiser.getRunParams();

        return HiPhiveOptions.builder()
                .diseaseId(diseaseId)
                .candidateGeneSymbol(candidateGeneSymbol)
                .runParams(runParams)
                .build();
    }
}
