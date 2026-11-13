import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter, Link, Route, Routes } from 'react-router';
import { ApiError } from '../api/client';
import { FhirResourcePage } from '../views/fhir/FhirResourcePage';
import { FhirPage } from '../views/fhir/FhirPage';
import { FhirToolsPage } from '../views/fhir/FhirToolsPage';
import { BronzeMessagePage } from '../views/lineage/BronzeMessagePage';
import { LineagePage } from '../views/lineage/LineagePage';
import { OntologyPage } from '../views/ontology/OntologyPage';
import { OverviewPage } from '../views/overview/OverviewPage';
import { CandidatePage } from '../views/patients/CandidatePage';
import { PatientDetailPage } from '../views/patients/PatientDetailPage';
import { PatientsPage } from '../views/patients/PatientsPage';
import { PmiPage } from '../views/patients/PmiPage';
import { RulesPage } from '../views/validation/RulesPage';
import { ValidationPage } from '../views/validation/ValidationPage';
import { AppServicesProvider, type AppServices } from './context';
import { Layout } from './Layout';

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 15_000,
        refetchOnWindowFocus: false,
        // The gateway already retries GETs once downstream; here only retry an unreachable gateway
        // or a 502/503/504 once. Never retry 4xx (bad request, 401 handled by the client, 403, 404).
        retry: (count, error) =>
          count < 1 && error instanceof ApiError && (error.status === 0 || error.status >= 502),
      },
      mutations: { retry: false },
    },
  });
}

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<OverviewPage />} />
        <Route path="patients" element={<PatientsPage />} />
        <Route path="patients/:masterId" element={<PatientDetailPage />} />
        <Route path="pmi" element={<PmiPage />} />
        <Route path="pmi/candidates/:candidateId" element={<CandidatePage />} />
        <Route path="ontology" element={<OntologyPage />} />
        <Route path="validation" element={<ValidationPage />} />
        <Route path="validation/rules" element={<RulesPage />} />
        <Route path="fhir" element={<FhirPage />} />
        <Route path="fhir/tools" element={<FhirToolsPage />} />
        <Route path="fhir/:type/:fhirId" element={<FhirResourcePage />} />
        <Route path="lineage" element={<LineagePage />} />
        <Route path="lineage/bronze/:messageId" element={<BronzeMessagePage />} />
        <Route path="*" element={<NotFound />} />
      </Route>
    </Routes>
  );
}

export function App({ services, queryClient }: { services: AppServices; queryClient: QueryClient }) {
  return (
    <AppServicesProvider value={services}>
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </QueryClientProvider>
    </AppServicesProvider>
  );
}

function NotFound() {
  return (
    <div className="empty">
      <h1>Page not found</h1>
      <p>
        <Link to="/">Back to the overview</Link>
      </p>
    </div>
  );
}
