package br.com.ia.compliance.application;

import br.com.ia.compliance.domain.Company;
import br.com.ia.compliance.domain.CompanyRepository;
import br.com.ia.compliance.domain.CompliancePolicy;
import br.com.ia.compliance.domain.ComplianceScreening;
import br.com.ia.compliance.infrastructure.rest.client.AntiMoneyLaunderingClient;
import br.com.ia.compliance.infrastructure.rest.client.SanctionClient;
import org.springframework.stereotype.Service;

@Service
public class AnalyzeCompanyRiskUseCase {
    private final SanctionClient sanctionClient;
    private final AntiMoneyLaunderingClient antiMoneyLaunderingClient;
    private final CompanyRepository companyRepository;

    public AnalyzeCompanyRiskUseCase(SanctionClient sanctionClient, AntiMoneyLaunderingClient antiMoneyLaunderingClient, CompanyRepository companyRepository) {
        this.sanctionClient = sanctionClient;
        this.antiMoneyLaunderingClient = antiMoneyLaunderingClient;
        this.companyRepository = companyRepository;
    }

    public void execute(Company company){
        var sanctions = sanctionClient.getCompanyRisk(company.getRegistrationNumber()).toDomain();
        var amlProfile = antiMoneyLaunderingClient.screening(company.getRegistrationNumber()).toDomain();

        var screening = new ComplianceScreening(sanctions, amlProfile);
        var riskAccessment = CompliancePolicy.evaluate(screening);

        company.applyRiskAccessment(riskAccessment);
        companyRepository.save(company);

    }
}
