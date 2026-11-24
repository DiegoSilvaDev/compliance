package br.com.ia.compliance.infrastructure.persistence.repository;

import br.com.ia.compliance.domain.Company;
import br.com.ia.compliance.domain.CompanyRepository;
import br.com.ia.compliance.infrastructure.persistence.entity.CompanyEntity;
import org.springframework.stereotype.Repository;

@Repository
public class InMemoryCompanyRepository implements CompanyRepository {
    private final CompanyEntityRepository repository;

    public InMemoryCompanyRepository(CompanyEntityRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(Company company) {
        var entity = CompanyEntity.from(company);
        repository.save(entity);
    }
}
